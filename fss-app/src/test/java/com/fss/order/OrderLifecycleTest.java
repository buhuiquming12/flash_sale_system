package com.fss.order;

import com.fss.biz.order.model.OrderVO;
import com.fss.biz.order.service.OrderService;
import com.fss.biz.payment.model.PayCreateCmd;
import com.fss.biz.payment.model.PayCreateVO;
import com.fss.biz.payment.model.PayNotifyCmd;
import com.fss.biz.payment.model.PayStatusVO;
import com.fss.biz.payment.service.PaymentService;
import com.fss.biz.seckill.model.SeckillCmd;
import com.fss.biz.seckill.model.SeckillSubmitVO;
import com.fss.biz.seckill.service.SeckillService;
import com.fss.common.enums.OrderStatus;
import com.fss.common.enums.PayStatus;
import com.fss.common.enums.StockChangeType;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.test.IntegrationTestBase;
import com.fss.test.TestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单生命周期验收：取消回补、重复回补幂等、C9 取消后重抢、C10 支付关单竞态、
 * C11 金额篡改、C12 越权查单。
 */
class OrderLifecycleTest extends IntegrationTestBase {

    @Autowired SeckillService seckillService;
    @Autowired OrderService   orderService;
    @Autowired PaymentService paymentService;
    @Autowired TestFixture    fixture;

    @Test
    @DisplayName("取消订单 → 库存回补，locked 归零，released 累计")
    void 取消回补库存() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        SeckillSubmitVO vo = submit(act, userId);
        TestFixture.StockSnapshot afterBuy = fixture.stock(act.activityId(), act.skuId());
        assertThat(afterBuy.available()).isEqualTo(9);
        assertThat(afterBuy.locked()).isEqualTo(1);

        orderService.cancel(vo.getOrderNo(), userId);

        TestFixture.StockSnapshot afterCancel = fixture.stock(act.activityId(), act.skuId());
        assertThat(afterCancel.available()).as("库存回到 10").isEqualTo(10);
        assertThat(afterCancel.locked()).isZero();
        assertThat(afterCancel.released()).isEqualTo(1);
        assertThat(afterCancel.identityHolds()).isTrue();

        assertThat(orderService.detail(vo.getOrderNo(), userId).getStatus())
                .isEqualTo(OrderStatus.CANCELLED.code());
        assertThat(fixture.countStockLog(vo.getOrderNo(), StockChangeType.CANCEL_RELEASE.code()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("C7/C8 重复关单与重复释放 → 库存只 +1，流水只 1 条")
    void 重复释放幂等() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = submit(act, userId);

        // 模拟消息重复投递 10 次
        for (int i = 0; i < 10; i++) {
            boolean closed = orderService.closeOrder(vo.getOrderNo(), "重复关单测试");
            assertThat(closed).as("第 %d 次关单", i + 1).isEqualTo(i == 0);
        }

        TestFixture.StockSnapshot s = fixture.stock(act.activityId(), act.skuId());
        assertThat(s.available()).as("库存只能 +1").isEqualTo(10);
        assertThat(s.released()).isEqualTo(1);
        assertThat(s.identityHolds()).isTrue();
        assertThat(fixture.countStockLog(vo.getOrderNo(), StockChangeType.CANCEL_RELEASE.code()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("C9 取消后重抢 → 3002，库存不变（决策 1）")
    void C9_取消后不可重抢() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        SeckillSubmitVO vo = submit(act, userId);
        orderService.cancel(vo.getOrderNo(), userId);
        int stockAfterCancel = fixture.availableStock(act.activityId(), act.skuId());
        assertThat(stockAfterCancel).isEqualTo(10);

        // uk_activity_sku_user 对已取消的订单同样生效
        assertThatThrownBy(() -> submit(act, userId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_BOUGHT);

        assertThat(fixture.availableStock(act.activityId(), act.skuId()))
                .as("重抢失败不能影响库存")
                .isEqualTo(stockAfterCancel);
        assertThat(fixture.countOrders(act.activityId(), act.skuId())).isEqualTo(1);
    }

    @Test
    @DisplayName("支付成功 → 订单已支付，locked 转 sold")
    void 支付成功() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = submit(act, userId);

        PayCreateVO pay = createPay(vo.getOrderNo(), userId);
        assertThat(pay.getAmount()).isEqualByComparingTo("4999.00");

        // 重复创建支付应复用同一条流水
        assertThat(createPay(vo.getOrderNo(), userId).getPayNo()).isEqualTo(pay.getPayNo());

        notifySuccess(pay);

        assertThat(orderService.detail(vo.getOrderNo(), userId).getStatus())
                .isEqualTo(OrderStatus.PAID.code());
        TestFixture.StockSnapshot s = fixture.stock(act.activityId(), act.skuId());
        assertThat(s.locked()).isZero();
        assertThat(s.sold()).isEqualTo(1);
        assertThat(s.available()).isEqualTo(9);
        assertThat(s.identityHolds()).isTrue();

        PayStatusVO st = paymentService.status(vo.getOrderNo(), userId);
        assertThat(st.getPayStatus()).isEqualTo(PayStatus.SUCCESS.code());

        // 已支付的订单不能被取消
        assertThatThrownBy(() -> orderService.cancel(vo.getOrderNo(), userId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_ILLEGAL);
    }

    @Test
    @DisplayName("重复回调 → 幂等，库存不重复搬移")
    void 重复回调幂等() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = submit(act, userId);
        PayCreateVO pay = createPay(vo.getOrderNo(), userId);

        for (int i = 0; i < 5; i++) {
            notifySuccess(pay);
        }

        TestFixture.StockSnapshot s = fixture.stock(act.activityId(), act.skuId());
        assertThat(s.sold()).as("sold 只能是 1").isEqualTo(1);
        assertThat(s.locked()).isZero();
        assertThat(s.identityHolds()).isTrue();
    }

    @Test
    @DisplayName("C11 金额篡改 → 5001，订单状态不变")
    void C11_金额篡改() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = submit(act, userId);
        PayCreateVO pay = createPay(vo.getOrderNo(), userId);

        Map<String, String> tampered = paymentService.mockSign(
                pay.getPayNo(), null, "0.01", "SUCCESS");
        assertThatThrownBy(() -> notify(tampered))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_AMOUNT_MISMATCH);

        assertThat(orderService.detail(vo.getOrderNo(), userId).getStatus())
                .isEqualTo(OrderStatus.PENDING_PAY.code());
    }

    @Test
    @DisplayName("验签失败 → 5003，且不泄漏任何订单信息")
    void 验签失败() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = submit(act, userId);
        PayCreateVO pay = createPay(vo.getOrderNo(), userId);

        Map<String, String> params = paymentService.mockSign(
                pay.getPayNo(), null, pay.getAmount().toPlainString(), "SUCCESS");
        params.put("sign", "deadbeef");

        assertThatThrownBy(() -> notify(params))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_SIGN_INVALID);

        // 不存在的 payNo + 错误签名，返回的也是验签失败而不是"订单不存在"
        Map<String, String> ghost = paymentService.mockSign(
                "P_NOT_EXIST", null, "1.00", "SUCCESS");
        ghost.put("sign", "deadbeef");
        assertThatThrownBy(() -> notify(ghost))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_SIGN_INVALID);
    }

    @Test
    @DisplayName("回调时间戳超出容忍窗口 → 拒绝（防重放）")
    void 时间戳防重放() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = submit(act, userId);
        PayCreateVO pay = createPay(vo.getOrderNo(), userId);

        Map<String, String> params = paymentService.mockSign(
                pay.getPayNo(), null, pay.getAmount().toPlainString(), "SUCCESS");
        // 把时间戳改到 10 分钟前，并重新签名 —— 签名合法但时间戳过期
        params.put("timestamp",
                String.valueOf(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10)));
        params.put("sign", com.fss.biz.payment.core.PaySignUtil.sign(
                params, "fss-integration-test-pay-secret"));

        assertThatThrownBy(() -> notify(params))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_SIGN_INVALID);
        assertThat(orderService.detail(vo.getOrderNo(), userId).getStatus())
                .isEqualTo(OrderStatus.PENDING_PAY.code());
    }

    @RepeatedTest(value = 30, name = "C10 支付与取消并发 第 {currentRepetition}/{totalRepetitions} 轮")
    @DisplayName("C10 支付与取消并发 → 恰好一方成功，无「已取消+已支付」双终态")
    void C10_支付关单竞态() throws Exception {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = submit(act, userId);
        PayCreateVO pay = createPay(vo.getOrderNo(), userId);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger paid = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            pool.submit(() -> {
                try {
                    start.await();
                    notifySuccess(pay);
                    paid.incrementAndGet();
                } catch (Exception ignored) {
                    // 抢输是合法结果
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    orderService.cancel(vo.getOrderNo(), userId);
                    cancelled.incrementAndGet();
                } catch (Exception ignored) {
                    // 抢输是合法结果
                } finally {
                    done.countDown();
                }
            });
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // 关键断言：订单终态只能是二者之一，绝不能出现"已取消且已支付"
        OrderVO order = orderService.detail(vo.getOrderNo(), userId);
        assertThat(order.getStatus())
                .as("订单终态必须是已支付或已取消之一")
                .isIn(OrderStatus.PAID.code(), OrderStatus.CANCELLED.code());

        TestFixture.StockSnapshot s = fixture.stock(act.activityId(), act.skuId());
        assertThat(s.identityHolds())
                .as("无论谁赢，库存等式都必须成立: %s", s)
                .isTrue();

        if (order.getStatus() == OrderStatus.PAID.code()) {
            assertThat(s.sold()).isEqualTo(1);
            assertThat(s.locked()).isZero();
            assertThat(s.available()).isEqualTo(9);
        } else {
            assertThat(s.sold()).isZero();
            assertThat(s.locked()).isZero();
            assertThat(s.available()).as("取消赢了则库存回补").isEqualTo(10);
        }
        assertThat(paid.get() + cancelled.get())
                .as("至少一方要成功，不能两边都失败让订单卡在待支付")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("C12 越权查单 → 与「不存在」返回同一个码，不泄漏订单号有效性")
    void C12_越权查单() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long owner = fixture.createUser();
        long other = fixture.createUser();
        SeckillSubmitVO vo = submit(act, owner);

        assertThatThrownBy(() -> orderService.detail(vo.getOrderNo(), other))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);

        assertThatThrownBy(() -> orderService.cancel(vo.getOrderNo(), other))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);

        assertThatThrownBy(() -> paymentService.create(payCmd(vo.getOrderNo()), other))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);

        // 订单仍然完好
        assertThat(orderService.detail(vo.getOrderNo(), owner).getStatus())
                .isEqualTo(OrderStatus.PENDING_PAY.code());
    }

    @Test
    @DisplayName("已过期订单不能支付")
    void 过期订单拒绝支付() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = submit(act, userId);

        fixture.expireOrder(vo.getOrderNo());

        assertThatThrownBy(() -> createPay(vo.getOrderNo(), userId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_EXPIRED);
    }

    // ------------------------------------------------------------------

    /**
     * 阶段三起秒杀是异步落库的，接口返回"排队中"而不是订单号。
     *
     * <p>这批用例的断言<b>一个字都没改</b>——它们验的是"取消要回补、支付要转 sold、
     * 越权要拒绝"这些对外行为，与订单是同步还是异步建出来的无关。
     * 变的只有这一行：等到消费端把订单建好再往下走。
     */
    private SeckillSubmitVO submit(TestFixture.Activity act, long userId) {
        return fixture.submitAndAwait(act, userId);
    }

    private PayCreateCmd payCmd(String orderNo) {
        PayCreateCmd cmd = new PayCreateCmd();
        cmd.setOrderNo(orderNo);
        return cmd;
    }

    private PayCreateVO createPay(String orderNo, long userId) {
        return paymentService.create(payCmd(orderNo), userId);
    }

    private void notifySuccess(PayCreateVO pay) {
        notify(paymentService.mockSign(pay.getPayNo(), null,
                pay.getAmount().toPlainString(), "SUCCESS"));
    }

    private void notify(Map<String, String> params) {
        PayNotifyCmd cmd = new PayNotifyCmd();
        cmd.setPayNo(params.get("payNo"));
        cmd.setOutTradeNo(params.get("outTradeNo"));
        cmd.setAmount(new BigDecimal(params.get("amount")));
        cmd.setStatus(params.get("status"));
        cmd.setTimestamp(Long.parseLong(params.get("timestamp")));
        cmd.setSign(params.get("sign"));

        // 验签用的原始参数集必须排除 sign 自身，与 Controller 的构造方式保持一致
        Map<String, String> raw = new java.util.LinkedHashMap<>(params);
        raw.remove("sign");
        paymentService.notify(cmd, raw);
    }
}

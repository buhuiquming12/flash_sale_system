package com.fss.reconcile;

import com.fss.biz.job.UncertainCheckJob;
import com.fss.biz.payment.model.PayCreateCmd;
import com.fss.biz.payment.model.PayCreateVO;
import com.fss.biz.payment.model.PayNotifyCmd;
import com.fss.biz.payment.service.PaymentService;
import com.fss.biz.seckill.core.UncertainRecorder;
import com.fss.biz.seckill.model.SeckillSubmitVO;
import com.fss.common.enums.OrderStatus;
import com.fss.common.enums.PayStatus;
import com.fss.common.enums.ReconcileTaskStatus;
import com.fss.common.enums.ReconcileTaskType;
import com.fss.domain.entity.Payment;
import com.fss.domain.mapper.PaymentMapper;
import com.fss.domain.mapper.ReconcileTaskMapper;
import com.fss.domain.message.OrderCreateMessage;
import com.fss.infra.metrics.SeckillMetrics;
import com.fss.infra.redis.RedisKeys;
import com.fss.test.IntegrationTestBase;
import com.fss.test.TestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不确定结果处理（F8）与支付关单竞态退款（F9）。
 *
 * <p>这两组用例的共同点是：<b>被测的分支在正常流程里永远不会走到</b>。
 * F8 需要 Redis 在脚本执行过程中超时，F9 需要支付回调与关单在同一毫秒竞争同一行。
 * 集成测试里没法可靠地制造这两种时序，所以用<b>等价输入</b>——
 * 直接往待确认集合里登记一条记录、直接把订单置成已取消再投递回调。
 * 验的是"给定这个状态，处置逻辑对不对"，那才是这两条路径的价值所在。
 */
@ActiveProfiles({"test", "consumer", "job"})
class UncertainAndRefundTest extends IntegrationTestBase {

    @Autowired UncertainCheckJob   checkJob;
    @Autowired UncertainRecorder   recorder;
    @Autowired PaymentService      paymentService;
    @Autowired PaymentMapper       paymentMapper;
    @Autowired ReconcileTaskMapper taskMapper;
    @Autowired SeckillMetrics      metrics;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate        jdbc;
    @Autowired TestFixture         fixture;

    // ==================================================================
    // F8 不确定结果
    // ==================================================================

    @Test
    @DisplayName("F8-a req key 存在 → 判定「脚本已执行」，补发消息，订单最终落库")
    void 已执行则补发消息() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        String requestNo = "R-UNCERTAIN-EXEC-" + System.nanoTime();

        // 制造"脚本其实执行成功了，只是响应丢了"这个状态：
        // 库存已扣、bought 已记、req key 已写，但没有任何消息被投递
        redis.opsForValue().decrement(RedisKeys.stock(act.activityId(), act.skuId()));
        redis.opsForHash().increment(RedisKeys.bought(act.activityId(), act.skuId()),
                String.valueOf(userId), 1);
        redis.opsForHash().putAll(
                RedisKeys.request(act.activityId(), act.skuId(), requestNo),
                Map.of("status", "0", "userId", String.valueOf(userId),
                        "ts", String.valueOf(System.currentTimeMillis())));
        recorder.record(msg(act, userId, requestNo));

        checkJob.checkUncertain();

        // 补发的消息会被消费端处理成一张真订单
        fixture.awaitOrders(act.activityId(), act.skuId(), 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM t_order WHERE request_no = ?", Long.class, requestNo))
                .as("req key 存在 ⟺ 库存已扣。这条等价关系成立，所以补发消息是安全的")
                .isEqualTo(1L);
        assertThat(recorder.size()).as("判定完成后记录必须移除").isZero();
    }

    @Test
    @DisplayName("F8-b req key 不存在 → 判定「脚本没执行」，什么都不做")
    void 未执行则不动() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        String requestNo = "R-UNCERTAIN-NOOP-" + System.nanoTime();
        long stockBefore = fixture.redisStock(act.activityId(), act.skuId());

        // 只登记待确认，不造 req key —— 对应"脚本根本没执行"
        recorder.record(msg(act, userId, requestNo));

        checkJob.checkUncertain();

        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("脚本没执行过，库存没动。这时回补就是凭空增加库存 → 直接超卖")
                .isEqualTo(stockBefore);
        assertThat(fixture.countOrders(act.activityId(), act.skuId())).isZero();
        assertThat(recorder.size()).isZero();
    }

    @Test
    @DisplayName("F8-c 静置窗口：刚登记的记录不参与判定")
    void 静置窗口() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        String requestNo = "R-UNCERTAIN-SETTLE-" + System.nanoTime();
        recorder.record(msg(act, userId, requestNo));

        assertThat(recorder.take(100, Duration.ofSeconds(5)))
                .as("超时的那一刻脚本可能<b>正在</b>执行。立刻 EXISTS 读到不存在就断定"
                        + "「没执行」，而 50ms 后它执行完了 —— 一份已扣的库存"
                        + "就成了只有库存对账才能发现的泄漏")
                .isEmpty();

        assertThat(recorder.take(100, Duration.ZERO))
                .as("静置窗口过去之后才可判定")
                .hasSize(1);

        // 清理，免得影响同一上下文里其他用例
        recorder.take(100, Duration.ZERO).forEach(r -> recorder.remove(r.raw()));
        assertThat(recorder.size()).isZero();
    }

    @Test
    @DisplayName("F8-d 移除记录必须用原始 JSON 串，重新序列化未必逐字节相同")
    void 按原始串移除() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        OrderCreateMessage m = msg(act, userId, "R-UNCERTAIN-RAW-" + System.nanoTime());
        recorder.record(m);

        var records = recorder.take(100, Duration.ZERO);
        assertThat(records).hasSize(1);
        recorder.remove(records.get(0).raw());

        assertThat(recorder.size())
                .as("ZSet 的 member 就是那个字符串。传 requestNo 或重新序列化一次去 ZREM "
                        + "会静默失败 0 条，症状是记录永远删不掉、每轮重复处理")
                .isZero();
    }

    @Test
    @DisplayName("F8-e 同一条记录重复判定是幂等的（补发用同一个 requestNo）")
    void 重复判定幂等() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        String requestNo = "R-UNCERTAIN-IDEM-" + System.nanoTime();

        redis.opsForValue().decrement(RedisKeys.stock(act.activityId(), act.skuId()));
        redis.opsForHash().increment(RedisKeys.bought(act.activityId(), act.skuId()),
                String.valueOf(userId), 1);
        redis.opsForHash().putAll(
                RedisKeys.request(act.activityId(), act.skuId(), requestNo),
                Map.of("status", "0", "userId", String.valueOf(userId),
                        "ts", String.valueOf(System.currentTimeMillis())));

        for (int i = 0; i < 3; i++) {
            recorder.record(msg(act, userId, requestNo));
            checkJob.checkUncertain();
        }

        fixture.awaitOrders(act.activityId(), act.skuId(), 1);
        assertThat(fixture.countOrders(act.activityId(), act.skuId()))
                .as("补发必须用同一个 requestNo：换新的会绕过 uk_request_no，"
                        + "一次预扣变成多张订单")
                .isEqualTo(1);
    }

    // ==================================================================
    // F9 支付与关单竞态 → 退款
    // ==================================================================

    @Test
    @DisplayName("F9 订单已取消而支付成功回调到达 → 记成功流水 + 标记退款 + 落对账任务")
    void 支付关单竞态转退款() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = fixture.submitAndAwait(act, userId);
        PayCreateVO pay = createPay(vo.getOrderNo(), userId);

        // 关单抢先赢了（走正常关单路径，库存回补给别人）
        jdbc.update("UPDATE t_order SET status = ?, cancel_time = NOW(3), "
                        + "cancel_reason = '模拟关单抢先' WHERE order_no = ?",
                OrderStatus.CANCELLED.code(), vo.getOrderNo());

        // 渠道回调随后到达，且带着"扣款成功"
        notifySuccess(pay);

        Payment p = paymentMapper.selectByPayNo(pay.getPayNo());
        assertThat(p.getStatus())
                .as("钱确实收了，账不能不认：先如实记成功流水，再走退款。"
                        + "顺序反了的话 refund 的 from=SUCCESS 条件更新一行都改不到")
                .isEqualTo(PayStatus.REFUNDED.code());
        assertThat(p.getOutTradeNo()).as("渠道流水号必须落库，退款要凭它").isNotNull();

        assertThat(orderStatus(vo.getOrderNo()))
                .as("CANCELLED 是终态，没有回头路 —— 库存已经还给别人了，"
                        + "拉回待支付再置已支付是用一次资损换一次超卖")
                .isEqualTo(OrderStatus.CANCELLED.code());

        assertThat(taskMapper.selectByType(ReconcileTaskType.PAYMENT.code(), 5))
                .anySatisfy(t -> {
                    assertThat(t.getDetail()).contains("PAY_CANCEL_RACE");
                    assertThat(t.getStatus())
                            .as("本项目没有真实渠道，REFUNDED 只表示「我们认为该退」，"
                                    + "钱有没有到用户账上必须有人确认")
                            .isEqualTo(ReconcileTaskStatus.NEED_MANUAL.code());
                });
        assertThat(metrics.counterValue("fss_alarm_total", "severity", "P1",
                "event", "PAY_CANCEL_RACE")).isGreaterThan(0);
    }

    @Test
    @DisplayName("F9-b 重复回调不会重复退款")
    void 退款幂等() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = fixture.submitAndAwait(act, userId);
        PayCreateVO pay = createPay(vo.getOrderNo(), userId);

        jdbc.update("UPDATE t_order SET status = ? WHERE order_no = ?",
                OrderStatus.CANCELLED.code(), vo.getOrderNo());

        notifySuccess(pay);
        long tasksAfterFirst = taskMapper.countOpen(ReconcileTaskType.PAYMENT.code());
        // 第二次回调：流水已是 REFUNDED，notify 的幂等分支不会再走到 paySuccess
        notifySuccess(pay);

        assertThat(paymentMapper.selectByPayNo(pay.getPayNo()).getStatus())
                .isEqualTo(PayStatus.REFUNDED.code());
        assertThat(taskMapper.countOpen(ReconcileTaskType.PAYMENT.code()))
                .as("重复回调不该再插一条对账任务")
                .isEqualTo(tasksAfterFirst);
    }

    // ------------------------------------------------------------------

    private OrderCreateMessage msg(TestFixture.Activity act, long userId, String requestNo) {
        return OrderCreateMessage.builder()
                .requestNo(requestNo)
                .userId(userId)
                .activityId(act.activityId())
                .skuId(act.skuId())
                .quantity(1)
                .requestTime(LocalDateTime.now())
                .traceId("uncertain-test")
                .version(OrderCreateMessage.CURRENT_VERSION)
                .build();
    }

    private int orderStatus(String orderNo) {
        return jdbc.queryForObject("SELECT status FROM t_order WHERE order_no = ?",
                Integer.class, orderNo);
    }

    private PayCreateVO createPay(String orderNo, long userId) {
        PayCreateCmd cmd = new PayCreateCmd();
        cmd.setOrderNo(orderNo);
        return paymentService.create(cmd, userId);
    }

    private void notifySuccess(PayCreateVO pay) {
        Map<String, String> params = paymentService.mockSign(pay.getPayNo(), null,
                pay.getAmount().toPlainString(), "SUCCESS");
        PayNotifyCmd cmd = new PayNotifyCmd();
        cmd.setPayNo(params.get("payNo"));
        cmd.setOutTradeNo(params.get("outTradeNo"));
        cmd.setAmount(new BigDecimal(params.get("amount")));
        cmd.setStatus(params.get("status"));
        cmd.setTimestamp(Long.parseLong(params.get("timestamp")));
        cmd.setSign(params.get("sign"));

        Map<String, String> raw = new java.util.LinkedHashMap<>(params);
        raw.remove("sign");
        paymentService.notify(cmd, raw);
    }
}

package com.fss.seckill;

import com.fss.biz.seckill.model.SeckillCmd;
import com.fss.biz.seckill.model.SeckillResultVO;
import com.fss.biz.seckill.model.SeckillSubmitVO;
import com.fss.biz.seckill.service.SeckillService;
import com.fss.common.enums.SeckillRequestStatus;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.test.Burst;
import com.fss.test.IntegrationTestBase;
import com.fss.test.TestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * 核心正确性验收：C1 超卖、C2 重复提交、C3 库存边界、C4/C5 时间窗口。
 *
 * <p>这些用例在阶段一（纯 MySQL 同步链路）就必须全绿——如果条件更新和唯一约束
 * 在这里就拦不住，后面加缓存只是把 bug 藏得更深。
 *
 * <p>阶段二起同一批断言的<b>拦截点变了</b>：库存不足与一人一单现在由 Lua 在 Redis
 * 里判掉，请求根本走不到 MySQL。断言本身一个字没改，这正是这批用例的价值：
 * 它们锁住的是"对外行为"，而不是某一层的实现。
 */
class SeckillConcurrencyTest extends IntegrationTestBase {

    @Autowired
    SeckillService seckillService;

    @Autowired
    TestFixture fixture;

    @Test
    @DisplayName("C1 库存 100 × 500 并发不同用户 → 恰好 100 单，库存归零，无负数")
    void C1_不超卖() throws Exception {
        int stock = 100;
        int concurrency = 500;
        TestFixture.Activity act = fixture.createRunningActivity(stock);
        List<Long> users = fixture.createUsers(concurrency);

        Burst.Result r = burst(concurrency, i -> submit(act, users.get(i)));

        assertThat(r.success.get())
                .as("成功数必须恰好等于库存")
                .isEqualTo(stock);
        assertThat(r.count(ErrorCode.STOCK_NOT_ENOUGH))
                .as("其余请求应全部是库存不足")
                .isEqualTo(concurrency - stock);
        assertThat(r.unexpected).as("不允许出现非业务异常").isEmpty();

        assertThat(fixture.countOrders(act.activityId(), act.skuId())).isEqualTo(stock);

        TestFixture.StockSnapshot s = fixture.stock(act.activityId(), act.skuId());
        assertThat(s.available()).as("可售库存归零").isZero();
        assertThat(s.locked()).as("全部转为待支付占用").isEqualTo(stock);
        assertThat(s.sold()).isZero();
        assertThat(s.identityHolds())
                .as("库存等式 total = available + locked + sold 必须成立: %s", s)
                .isTrue();
    }

    @Test
    @DisplayName("C2 同一用户并发 50 次 → 恰好 1 单，其余 3002")
    void C2_一人一单() throws Exception {
        TestFixture.Activity act = fixture.createRunningActivity(100);
        long userId = fixture.createUser();

        Burst.Result r = burst(50, i -> submit(act, userId));

        assertThat(r.success.get()).isEqualTo(1);
        assertThat(r.count(ErrorCode.ALREADY_BOUGHT)).isEqualTo(49);
        assertThat(r.unexpected).isEmpty();

        assertThat(fixture.countOrders(act.activityId(), act.skuId())).isEqualTo(1);
        TestFixture.StockSnapshot s = fixture.stock(act.activityId(), act.skuId());
        assertThat(s.available())
                .as("失败的 49 次不能白吃库存")
                .isEqualTo(99);
        assertThat(s.locked()).isEqualTo(1);
        assertThat(s.identityHolds()).isTrue();
    }

    @Test
    @DisplayName("C3 库存 1 × 200 并发 → 恰好 1 单")
    void C3_库存边界() throws Exception {
        TestFixture.Activity act = fixture.createRunningActivity(1);
        List<Long> users = fixture.createUsers(200);

        Burst.Result r = burst(200, i -> submit(act, users.get(i)));

        assertThat(r.success.get()).isEqualTo(1);
        assertThat(r.unexpected).isEmpty();
        assertThat(fixture.countOrders(act.activityId(), act.skuId())).isEqualTo(1);

        TestFixture.StockSnapshot s = fixture.stock(act.activityId(), act.skuId());
        assertThat(s.available()).isZero();
        assertThat(s.identityHolds()).isTrue();
    }

    @Test
    @DisplayName("C4 活动未开始 → 全部 2002，无订单")
    void C4_活动未开始() {
        TestFixture.Activity act = fixture.createNotStartedActivity(100);
        long userId = fixture.createUser();

        assertThatThrownBy(() -> submit(act, userId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVITY_NOT_START);

        assertThat(fixture.countOrders(act.activityId(), act.skuId())).isZero();
        assertThat(fixture.availableStock(act.activityId(), act.skuId())).isEqualTo(100);
    }

    @Test
    @DisplayName("C5 活动已结束 → 全部 2003，无订单")
    void C5_活动已结束() {
        TestFixture.Activity act = fixture.createEndedActivity(100);
        long userId = fixture.createUser();

        assertThatThrownBy(() -> submit(act, userId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVITY_ENDED);

        assertThat(fixture.countOrders(act.activityId(), act.skuId())).isZero();
    }

    @Test
    @DisplayName("失败请求也要留下可查询的结论，不能让用户轮询到超时")
    void 失败结论可查询() {
        TestFixture.Activity act = fixture.createRunningActivity(1);
        long u1 = fixture.createUser();
        long u2 = fixture.createUser();

        SeckillSubmitVO ok = submit(act, u1);
        assertThat(ok.getOrderNo()).isNotBlank();

        try {
            submit(act, u2);
            fail("库存已耗尽，第二个用户应当失败");
        } catch (BizException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);
        }

        // 成功的请求能查到订单
        SeckillResultVO r1 = seckillService.queryResult(ok.getRequestNo(), u1);
        assertThat(r1.getStatus()).isEqualTo(SeckillRequestStatus.SUCCESS.code());
        assertThat(r1.getOrderNo()).isEqualTo(ok.getOrderNo());

        // 越权查询与"不存在"返回同一个码，不泄漏请求号有效性
        assertThatThrownBy(() -> seckillService.queryResult(ok.getRequestNo(), u2))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REQUEST_NOT_FOUND);
        assertThatThrownBy(() -> seckillService.queryResult("R_NOT_EXIST_0001", u2))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REQUEST_NOT_FOUND);
    }

    @Test
    @DisplayName("C13 数量必须为 1，传其他值直接拒绝而非静默改成 1")
    void C13_数量校验() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        assertThatThrownBy(() -> seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 2), userId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);

        assertThat(fixture.availableStock(act.activityId(), act.skuId())).isEqualTo(10);
    }

    // ------------------------------------------------------------------
    // 并发压测脚手架见 com.fss.test.Burst
    // ------------------------------------------------------------------

    private SeckillSubmitVO submit(TestFixture.Activity act, long userId) {
        return seckillService.submit(SeckillCmd.of(act.activityId(), act.skuId(), 1), userId);
    }

    private Burst.Result burst(int concurrency, java.util.function.IntConsumer task)
            throws Exception {
        return Burst.run(concurrency, task);
    }
}

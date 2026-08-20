package com.fss.job;

import com.fss.biz.job.ActivityStateJob;
import com.fss.biz.job.OrderCloseJob;
import com.fss.biz.job.WarmupJob;
import com.fss.biz.order.model.OrderVO;
import com.fss.biz.order.service.OrderService;
import com.fss.biz.seckill.model.SeckillCmd;
import com.fss.biz.seckill.model.SeckillSubmitVO;
import com.fss.biz.seckill.service.SeckillService;
import com.fss.common.enums.ActivityStatus;
import com.fss.common.enums.OrderStatus;
import com.fss.common.enums.WarmupState;
import com.fss.test.IntegrationTestBase;
import com.fss.test.TestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定时任务验收。
 *
 * <p>激活 {@code job} profile 以装配任务 Bean，但把 cron 设成"每年 1 月 1 日"
 * 让它在测试期间不会自动触发——由测试显式调用。<b>不这样做的话，任务会在测试断言
 * 之间随机把订单关掉</b>，产生只在 CI 上偶发的失败。
 */
@ActiveProfiles({"test", "job"})
@TestPropertySource(properties = {
        "fss.job.close-expired-cron=0 0 0 1 1 ?",
        "fss.job.activity-state-cron=0 0 0 1 1 ?",
        "fss.job.warmup-cron=0 0 0 1 1 ?"})
class ScheduledJobTest extends IntegrationTestBase {

    @Autowired OrderCloseJob     orderCloseJob;
    @Autowired ActivityStateJob  activityStateJob;
    @Autowired WarmupJob         warmupJob;
    @Autowired SeckillService    seckillService;
    @Autowired OrderService      orderService;
    @Autowired TestFixture       fixture;
    @Autowired JdbcTemplate      jdbc;

    @Test
    @DisplayName("超时扫描关闭待支付订单并回补库存")
    void 超时关单() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId);

        assertThat(fixture.availableStock(act.activityId(), act.skuId())).isEqualTo(9);

        fixture.expireOrder(vo.getOrderNo());
        orderCloseJob.closeExpiredOrders();

        OrderVO order = orderService.detail(vo.getOrderNo(), userId);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED.code());
        assertThat(order.getCancelReason()).contains("超时");

        TestFixture.StockSnapshot s = fixture.stock(act.activityId(), act.skuId());
        assertThat(s.available()).isEqualTo(10);
        assertThat(s.locked()).isZero();
        assertThat(s.released()).isEqualTo(1);
        assertThat(s.identityHolds()).isTrue();
    }

    @Test
    @DisplayName("未过期订单不应被扫描关闭")
    void 未过期订单不动() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId);

        orderCloseJob.closeExpiredOrders();

        assertThat(orderService.detail(vo.getOrderNo(), userId).getStatus())
                .isEqualTo(OrderStatus.PENDING_PAY.code());
        assertThat(fixture.availableStock(act.activityId(), act.skuId())).isEqualTo(9);
    }

    @Test
    @DisplayName("重复执行扫描 → 库存只回补一次")
    void 扫描可重复执行() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        SeckillSubmitVO vo = seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId);

        fixture.expireOrder(vo.getOrderNo());
        for (int i = 0; i < 3; i++) {
            orderCloseJob.closeExpiredOrders();
        }

        TestFixture.StockSnapshot s = fixture.stock(act.activityId(), act.skuId());
        assertThat(s.available()).isEqualTo(10);
        assertThat(s.released()).as("released 只能累计 1").isEqualTo(1);
        assertThat(s.identityHolds()).isTrue();
    }

    @Test
    @DisplayName("F13 预热未完成的活动不会进入 RUNNING")
    void 未预热活动不进入进行中() {
        long activityId = createReadyActivity(WarmupState.NONE);

        activityStateJob.advanceState();

        assertThat(statusOf(activityId))
                .as("warmup_state != 2 时必须留在待开始，用户看到「即将开始」而不是系统错误")
                .isEqualTo(ActivityStatus.READY.code());
    }

    @Test
    @DisplayName("预热完成且到点的活动推进为进行中；到结束时间推进为已结束并停售")
    void 活动状态自动推进() {
        long activityId = createReadyActivity(WarmupState.DONE);

        activityStateJob.advanceState();
        assertThat(statusOf(activityId)).isEqualTo(ActivityStatus.RUNNING.code());

        // 把结束时间调到过去，再推进
        jdbc.update("UPDATE t_seckill_activity SET end_time = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), activityId);
        activityStateJob.advanceState();

        assertThat(statusOf(activityId)).isEqualTo(ActivityStatus.ENDED.code());
        Integer goodsStatus = jdbc.queryForObject(
                "SELECT status FROM t_seckill_goods WHERE activity_id = ?",
                Integer.class, activityId);
        assertThat(goodsStatus).as("活动结束后商品应停售，让后续请求快速失败").isZero();
    }

    /** 造一个"已发布、开始时间已到、结束时间未到"的活动，预热状态由参数指定 */
    private long createReadyActivity(WarmupState warmup) {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        jdbc.update("""
                UPDATE t_seckill_activity
                   SET status = ?, warmup_state = ?, start_time = ?, end_time = ?
                 WHERE id = ?
                """,
                ActivityStatus.READY.code(), warmup.code(),
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1),
                act.activityId());
        return act.activityId();
    }

    @Test
    @DisplayName("预热任务 → 状态任务：完整的「开抢前预热、到点推进」流水线")
    void 预热任务与状态推进串起来() {
        long activityId = createReadyActivity(WarmupState.NONE);

        // 只跑状态任务：预热没做，活动必须留在待开始（F13）
        activityStateJob.advanceState();
        assertThat(statusOf(activityId)).isEqualTo(ActivityStatus.READY.code());

        // 预热任务先把它预热并置位
        warmupJob.warmup();
        assertThat(warmupStateOf(activityId))
                .as("预热任务必须把 warmup_state 置成 DONE，否则活动永远进不了 RUNNING")
                .isEqualTo(WarmupState.DONE.code());

        // 再跑状态任务，这次才推进
        activityStateJob.advanceState();
        assertThat(statusOf(activityId))
                .as("顺序反了就是「到点开抢但 Redis 没数据」，"
                        + "全部请求返回 2005 —— 这条流水线的顺序是整个阶段二的地基")
                .isEqualTo(ActivityStatus.RUNNING.code());
    }

    @Test
    @DisplayName("预热任务重复执行不会重置已扣减的库存")
    void 预热任务可重复执行() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        seckillService.submit(SeckillCmd.of(act.activityId(), act.skuId(), 1), userId);
        assertThat(fixture.redisStock(act.activityId(), act.skuId())).isEqualTo(9L);

        // 把活动改回"待开始且未预热"，让预热任务重新捞到它
        jdbc.update("UPDATE t_seckill_activity SET status = ?, warmup_state = ? WHERE id = ?",
                ActivityStatus.READY.code(), WarmupState.NONE.code(), act.activityId());
        warmupJob.warmup();

        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("setIfAbsent 保证重复预热不覆盖库存。变回 10 就是超卖 1 件")
                .isEqualTo(9L);
    }

    private int warmupStateOf(long activityId) {
        return jdbc.queryForObject(
                "SELECT warmup_state FROM t_seckill_activity WHERE id = ?",
                Integer.class, activityId);
    }

    private int statusOf(long activityId) {
        return jdbc.queryForObject("SELECT status FROM t_seckill_activity WHERE id = ?",
                Integer.class, activityId);
    }
}

package com.fss.seckill;

import com.fss.biz.activity.service.ActivityService;
import com.fss.biz.activity.service.WarmupService;
import com.fss.biz.seckill.model.SeckillCmd;
import com.fss.biz.seckill.service.SeckillService;
import com.fss.common.enums.ActivityStatus;
import com.fss.common.enums.WarmupState;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.domain.entity.SeckillGoods;
import com.fss.test.IntegrationTestBase;
import com.fss.test.TestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 预热验收。
 *
 * <p>核心是一条：<b>重复预热绝不能重置已扣减的库存</b>。这是 {@code setIfAbsent}
 * 存在的全部理由，也是最容易在重构中被"顺手改成 set"毁掉的一行——
 * 改完之后功能测试全绿，只有在"活动进行中恰好重跑了一次预热"时才超卖。
 */
class WarmupTest extends IntegrationTestBase {

    @Autowired WarmupService   warmupService;
    @Autowired SeckillService  seckillService;
    @Autowired ActivityService activityService;
    @Autowired TestFixture     fixture;
    @Autowired JdbcTemplate    jdbc;

    @Test
    @DisplayName("W1 活动进行中重复预热 → Redis 库存保持已扣减的值，不被重置")
    void W1_重复预热不重置库存() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long u1 = fixture.createUser();
        long u2 = fixture.createUser();

        seckillService.submit(SeckillCmd.of(act.activityId(), act.skuId(), 1), u1);
        seckillService.submit(SeckillCmd.of(act.activityId(), act.skuId(), 1), u2);
        assertThat(fixture.redisStock(act.activityId(), act.skuId())).isEqualTo(8L);

        // 人为误操作 / 任务重复触发 / job 实例重启后补跑一轮，都会走到这里
        for (int i = 0; i < 3; i++) {
            warmupService.warmupOne(act.activityId());
        }

        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("这里如果变回 10，说明预热用了 SET 而不是 SETNX —— "
                        + "那两个已经拿到资格的用户扣掉的库存就凭空回来了，直接超卖 2 件")
                .isEqualTo(8L);
        assertThat(fixture.redisBought(act.activityId(), act.skuId(), u1))
                .as("重复预热也不该动购买标记")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("W2 重复预热会刷新元数据：改了活动时间，重新预热后 Lua 按新时间判定")
    void W2_元数据可被覆盖() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        // 把 DB 的时间窗口改成"已结束"，再重新预热
        jdbc.update("UPDATE t_seckill_activity SET start_time = ?, end_time = ? WHERE id = ?",
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusMinutes(1),
                act.activityId());
        warmupService.warmupOne(act.activityId());

        assertThatThrownBy(() -> seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId))
                .as("元数据是活动配置的投影，必须可以被无条件覆盖；"
                        + "只有库存不能覆盖。两者的处理方式不同，这是预热的关键区分")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVITY_ENDED);
    }

    @Test
    @DisplayName("W3 对已结束的活动预热不会崩：TTL 有下限，不会写出负数过期时间")
    void W3_已结束活动预热不报错() {
        TestFixture.Activity act = fixture.createEndedActivity(10);

        // createEndedActivity 内部已经预热过一次（end_time 在 1 小时前），
        // 这里再来一次，确认 TTL 计算的下限保护确实生效。
        // 没有下限保护时 EXPIRE 会收到负数，Redis 直接报
        // "invalid expire time"，整个预热在第一个商品上就炸掉
        warmupService.warmupOne(act.activityId());

        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("key 必须真的写进去了（负数 TTL 会导致 SET 失败或立刻过期）")
                .isEqualTo(10L);
        assertThat(fixture.redisGoodsStatus(act.activityId(), act.skuId()))
                .isEqualTo(SeckillGoods.STATUS_ON_SALE);
    }

    @Test
    @DisplayName("W4 预热会置 warmup_state=DONE，这是活动能进入 RUNNING 的前提（F13）")
    void W4_预热置位() {
        TestFixture.Activity act = fixture.createRunningActivityWithoutWarmup(10);
        jdbc.update("UPDATE t_seckill_activity SET status = ?, warmup_state = ? WHERE id = ?",
                ActivityStatus.READY.code(), WarmupState.NONE.code(), act.activityId());

        assertThat(warmupStateOf(act.activityId())).isEqualTo(WarmupState.NONE.code());

        warmupService.warmupOne(act.activityId());

        assertThat(warmupStateOf(act.activityId()))
                .as("阶段一在 publish 里直接置 DONE 当替身，阶段二必须由真正的预热来置位；"
                        + "留着那个替身会让「未预热不进 RUNNING」这条保护彻底失效")
                .isEqualTo(WarmupState.DONE.code());
    }

    @Test
    @DisplayName("W5 草稿状态的活动不允许预热")
    void W5_状态校验() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        jdbc.update("UPDATE t_seckill_activity SET status = ? WHERE id = ?",
                ActivityStatus.DRAFT.code(), act.activityId());

        assertThatThrownBy(() -> warmupService.warmupOne(act.activityId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVITY_STATUS_ILLEGAL);
    }

    @Test
    @DisplayName("W6 关闭活动 → Redis 侧立即停售，Lua 拒绝新请求")
    void W6_关闭活动同步到Redis() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        activityService.close(act.activityId(), act.adminId());

        assertThat(fixture.redisGoodsStatus(act.activityId(), act.skuId()))
                .as("只改 DB 不改 Redis 的话，Lua 仍按时间窗口放行，"
                        + "管理员点了「关闭」却毫无效果")
                .isEqualTo(SeckillGoods.STATUS_OFF_SALE);

        assertThatThrownBy(() -> seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.GOODS_OFF_SALE);
    }

    private int warmupStateOf(long activityId) {
        return jdbc.queryForObject(
                "SELECT warmup_state FROM t_seckill_activity WHERE id = ?",
                Integer.class, activityId);
    }
}

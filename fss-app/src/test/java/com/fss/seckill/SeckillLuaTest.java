package com.fss.seckill;

import com.fss.biz.seckill.model.SeckillCmd;
import com.fss.biz.seckill.model.SeckillResultVO;
import com.fss.biz.seckill.model.SeckillSubmitVO;
import com.fss.biz.seckill.service.SeckillService;
import com.fss.common.enums.SeckillRequestStatus;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.domain.entity.SeckillGoods;
import com.fss.test.Burst;
import com.fss.test.IntegrationTestBase;
import com.fss.test.TestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段二验收：Lua 原子判扣。
 *
 * <p>对应 docs/08 §1 阶段二的四条验收标准：
 * <pre>
 * 1000 库存 × 10000 并发   → Redis 剩余 0，DB 订单恰好 1000
 * 重复预热                 → Redis 库存不被重置        （见 WarmupTest）
 * 活动结束瞬间的请求        → 全部被 Lua 时间校验拒绝
 * 限流：单用户 100 QPS     → 大部分返回 429            （见 RateLimitTest）
 * </pre>
 */
class SeckillLuaTest extends IntegrationTestBase {

    @Autowired SeckillService seckillService;
    @Autowired TestFixture    fixture;
    @Autowired JdbcTemplate   jdbc;

    @Test
    @DisplayName("P1 库存 1000 × 10000 并发 → DB 订单恰好 1000，Redis 剩余 0，无负数")
    void P1_万级并发不超卖() throws Exception {
        int stock = 1000;
        int concurrency = 10_000;
        TestFixture.Activity act = fixture.createRunningActivity(stock);
        List<Long> users = fixture.createUsers(concurrency);

        Burst.Result r = Burst.run(concurrency, 64,
                i -> seckillService.submit(
                        SeckillCmd.of(act.activityId(), act.skuId(), 1), users.get(i)));

        assertThat(r.unexpected).as("不允许出现非业务异常").isEmpty();
        assertThat(r.success.get()).as("成功数必须恰好等于库存: %s", r).isEqualTo(stock);
        assertThat(r.count(ErrorCode.STOCK_NOT_ENOUGH))
                .as("其余请求全部应是库存不足（Lua 判掉，根本没走到 MySQL）")
                .isEqualTo(concurrency - stock);

        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("Redis 库存必须归零，且绝不能为负")
                .isZero();
        assertThat(fixture.countOrders(act.activityId(), act.skuId())).isEqualTo(stock);

        TestFixture.StockSnapshot s = fixture.stock(act.activityId(), act.skuId());
        assertThat(s.available()).isZero();
        assertThat(s.locked()).isEqualTo(stock);
        assertThat(s.identityHolds())
                .as("库存等式 total = available + locked + sold 必须成立: %s", s)
                .isTrue();
    }

    @Test
    @DisplayName("P2 售罄后 Redis 打上 status=2 快速失败标记，后续请求两步内返回")
    void P2_售罄快速失败() {
        TestFixture.Activity act = fixture.createRunningActivity(1);
        long u1 = fixture.createUser();
        long u2 = fixture.createUser();

        assertThat(fixture.redisGoodsStatus(act.activityId(), act.skuId()))
                .as("预热后应为在售")
                .isEqualTo(SeckillGoods.STATUS_ON_SALE);

        seckillService.submit(SeckillCmd.of(act.activityId(), act.skuId(), 1), u1);

        assertThat(fixture.redisGoodsStatus(act.activityId(), act.skuId()))
                .as("库存归零时 Lua 应把 status 改成售罄，让后续请求在第二步就返回，"
                        + "而不是每次都走完 EXISTS→HMGET→TIME→HGET→GET 五次操作")
                .isEqualTo(SeckillGoods.STATUS_SOLD_OUT);

        // 对外仍然是"库存不足"而不是"商品已停售"：售罄和管理员停售必须是不同的错误码，
        // 否则用户看到的提示是"下架了"，客服会收到一堆"明明还有货"的工单
        assertThatThrownBy(() -> seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), u2))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);
    }

    @Test
    @DisplayName("P3 活动结束瞬间的请求 → 全部被 Lua 时间校验拒绝，无订单产生")
    void P3_活动结束瞬间全部拒绝() throws Exception {
        TestFixture.Activity act = fixture.createRunningActivity(100);
        List<Long> users = fixture.createUsers(200);

        // 只改 Redis 里的结束时间到"刚刚"，DB 的时间窗口保持开着。
        // 这样能证明拒绝确实来自 Lua 而不是别处的兜底校验
        fixture.overrideRedisWindow(act.activityId(), act.skuId(),
                LocalDateTime.now().minusHours(1), LocalDateTime.now().minusSeconds(1));

        Burst.Result r = Burst.run(200, 32,
                i -> seckillService.submit(
                        SeckillCmd.of(act.activityId(), act.skuId(), 1), users.get(i)));

        assertThat(r.success.get()).as("活动已结束，一个都不该成功").isZero();
        assertThat(r.count(ErrorCode.ACTIVITY_ENDED)).isEqualTo(200);
        assertThat(r.unexpected).isEmpty();

        assertThat(fixture.countOrders(act.activityId(), act.skuId())).isZero();
        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("被时间校验拒绝的请求不能吃掉库存")
                .isEqualTo(100L);
    }

    @Test
    @DisplayName("F14 时钟以 Redis 为准：应用时钟说活动进行中，Redis 说未开始 → 拒绝")
    void F14_时间判定只认Redis时钟() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        // DB 里 start_time 在 1 分钟前（fixture.createRunningActivity 就是这么造的），
        // 应用侧任何用 LocalDateTime.now() 的判断都会认为"进行中"。
        // 只把 Redis 里的 startTime 推到 10 分钟后 —— 相当于模拟一台时钟快了 10 分钟的
        // web 实例：它自己觉得活动开了，但唯一有权威的时钟（Redis）说没开
        fixture.overrideRedisWindow(act.activityId(), act.skuId(),
                LocalDateTime.now().plusMinutes(10), LocalDateTime.now().plusHours(2));

        assertThatThrownBy(() -> seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId))
                .as("时间判定必须只有一个权威时钟。这里如果通过了，说明落库路径上"
                        + "还留着一处用应用时钟的时间校验，多实例时钟漂移时就会提前放行")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVITY_NOT_START);

        assertThat(fixture.countOrders(act.activityId(), act.skuId())).isZero();
        assertThat(fixture.redisStock(act.activityId(), act.skuId())).isEqualTo(10L);
    }

    @Test
    @DisplayName("P5 未预热的活动 → 返回 2005 而不是超卖，DB 库存分毫不动")
    void P5_未预热直接拒绝() {
        TestFixture.Activity act = fixture.createRunningActivityWithoutWarmup(10);
        long userId = fixture.createUser();

        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("前提：Redis 里确实没有库存 key")
                .isNull();

        assertThatThrownBy(() -> seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId))
                .as("Redis 没数据时必须 fail-closed。放行会让 MySQL 成为唯一防线，"
                        + "而那正是引入 Redis 要避免的热点行竞争")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.GOODS_NOT_WARMED);

        assertThat(fixture.countOrders(act.activityId(), act.skuId())).isZero();
        assertThat(fixture.availableStock(act.activityId(), act.skuId())).isEqualTo(10);

        // 补预热后立刻恢复可用（对应 F2：Redis 数据丢失后人工补数据）
        fixture.warmup(act.activityId());
        SeckillSubmitVO vo = seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), userId);
        assertThat(vo.getOrderNo()).isNotBlank();
        assertThat(fixture.redisStock(act.activityId(), act.skuId())).isEqualTo(9L);
    }

    @Test
    @DisplayName("P6 一人一单在 Redis 就被拦下：单用户并发 50 次，bought 恒为 1")
    void P6_一人一单拦在Redis() throws Exception {
        TestFixture.Activity act = fixture.createRunningActivity(100);
        long userId = fixture.createUser();

        Burst.Result r = Burst.run(50, 32,
                i -> seckillService.submit(
                        SeckillCmd.of(act.activityId(), act.skuId(), 1), userId));

        assertThat(r.success.get()).isEqualTo(1);
        assertThat(r.count(ErrorCode.ALREADY_BOUGHT)).isEqualTo(49);
        assertThat(r.unexpected).isEmpty();

        assertThat(fixture.redisBought(act.activityId(), act.skuId(), userId))
                .as("Lua 先判 bought 再扣库存，所以失败的 49 次一次都没碰过库存")
                .isEqualTo(1L);
        assertThat(fixture.redisStock(act.activityId(), act.skuId())).isEqualTo(99L);
        assertThat(fixture.countOrders(act.activityId(), act.skuId())).isEqualTo(1);
    }

    @Test
    @DisplayName("P7 结果查询命中 Redis：带 activityId/skuId 时不回查数据库也能拿到结论")
    void P7_结果查询命中Redis() {
        TestFixture.Activity act = fixture.createRunningActivity(1);
        long u1 = fixture.createUser();
        long u2 = fixture.createUser();

        SeckillSubmitVO ok = seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), u1);

        SeckillResultVO r = seckillService.queryResult(
                ok.getRequestNo(), act.activityId(), act.skuId(), u1);
        assertThat(r.getStatus()).isEqualTo(SeckillRequestStatus.SUCCESS.code());
        assertThat(r.getOrderNo()).isEqualTo(ok.getOrderNo());

        // 越权查询与"不存在"返回同一个码，不泄漏请求号有效性。
        // Redis 的 req hash 里存了 userId，归属校验在读到之后立刻做
        assertThatThrownBy(() -> seckillService.queryResult(
                ok.getRequestNo(), act.activityId(), act.skuId(), u2))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REQUEST_NOT_FOUND);
    }

    @Test
    @DisplayName("P8 被 Lua 拒绝的请求也留下可查询的结论，不让客户端轮询到超时")
    void P8_失败结论也写进Redis() {
        TestFixture.Activity act = fixture.createRunningActivity(1);
        long u1 = fixture.createUser();
        long u2 = fixture.createUser();

        seckillService.submit(SeckillCmd.of(act.activityId(), act.skuId(), 1), u1);

        // u2 撞上库存不足。Lua 在库存校验那一步就 return 了，没有创建 req key，
        // 所以结论是服务端补写进去的
        assertThatThrownBy(() -> seckillService.submit(
                SeckillCmd.of(act.activityId(), act.skuId(), 1), u2))
                .isInstanceOf(BizException.class);

        String failedRequestNo = jdbc.queryForObject(
                "SELECT request_no FROM t_seckill_request WHERE user_id = ? AND status = ?",
                String.class, u2, SeckillRequestStatus.STOCK_NOT_ENOUGH.code());
        assertThat(failedRequestNo).as("失败也要落 t_seckill_request，供对账与查询").isNotBlank();

        SeckillResultVO r = seckillService.queryResult(
                failedRequestNo, act.activityId(), act.skuId(), u2);
        assertThat(r.getStatus())
                .as("Redis 里应能读到明确的失败结论，而不是查不到")
                .isEqualTo(SeckillRequestStatus.STOCK_NOT_ENOUGH.code());
        assertThat(r.getPollAfterMs()).as("终态不该让客户端继续轮询").isZero();
    }
}

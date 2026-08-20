package com.fss.activity;

import com.fss.biz.activity.model.ActivityDetailVO;
import com.fss.biz.activity.service.ActivityService;
import com.fss.biz.seckill.model.SeckillCmd;
import com.fss.biz.seckill.service.SeckillService;
import com.fss.common.enums.ActivityStatus;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.common.util.JsonUtil;
import com.fss.infra.cache.CacheWrapper;
import com.fss.infra.redis.RedisKeys;
import com.fss.test.IntegrationTestBase;
import com.fss.test.TestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 活动详情缓存验收：逻辑过期 + 单飞重建 + 空值缓存 + TTL 抖动。
 *
 * <p>这批用例里最重要的不是"缓存能不能命中"，而是<b>哪些字段不能被缓存</b>：
 * 一个 2 小时 TTL 的缓存如果把 {@code serverTime} 和库存也一起冻住，
 * 客户端的倒计时会停在两小时前、库存永远显示满的。功能测试全绿，
 * 而演示时一眼就能看出不对。
 */
class ActivityCacheTest extends IntegrationTestBase {

    @Autowired ActivityService    activityService;
    @Autowired SeckillService     seckillService;
    @Autowired TestFixture        fixture;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate       jdbc;

    @Test
    @DisplayName("A1 第二次读命中缓存：改了数据库也读到旧值（证明没回源）")
    void A1_命中缓存() {
        TestFixture.Activity act = fixture.createRunningActivity(10);

        String first = activityService.detail(act.activityId()).getName();
        assertThat(redis.hasKey(RedisKeys.activityDetail(act.activityId())))
                .as("回源之后应当写入缓存")
                .isTrue();

        jdbc.update("UPDATE t_seckill_activity SET name = ? WHERE id = ?",
                "改过的名字", act.activityId());

        assertThat(activityService.detail(act.activityId()).getName())
                .as("逻辑未过期时必须直接返回缓存，不查库。读到新名字说明缓存没生效")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("A2 serverTime 每次重算，不被缓存冻结")
    void A2_服务端时间不被缓存() throws Exception {
        TestFixture.Activity act = fixture.createRunningActivity(10);

        LocalDateTime t1 = activityService.detail(act.activityId()).getServerTime();
        Thread.sleep(1100);
        LocalDateTime t2 = activityService.detail(act.activityId()).getServerTime();

        assertThat(t2)
                .as("客户端靠 serverTime 校准倒计时。缓存住它，"
                        + "所有客户端的倒计时都会停在缓存写入的那一刻")
                .isAfter(t1);
    }

    @Test
    @DisplayName("A3 库存取 Redis 实时值，不用缓存里的快照")
    void A3_库存实时() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();

        assertThat(remainStock(act)).isEqualTo(10);

        seckillService.submit(SeckillCmd.of(act.activityId(), act.skuId(), 1), userId);

        assertThat(remainStock(act))
                .as("缓存里那份是写入时的 DB 快照。库存必须读 Redis —— "
                        + "阶段三异步化后 DB 的 available_stock 还会滞后于真实可抢量")
                .isEqualTo(9);
    }

    @Test
    @DisplayName("A4 不存在的活动 → 写入空值缓存，后续请求不再打数据库（防穿透）")
    void A4_空值缓存() {
        long ghostId = 999_999_999L;
        String key = RedisKeys.activityDetail(ghostId);
        redis.delete(key);

        assertThatThrownBy(() -> activityService.detail(ghostId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVITY_NOT_FOUND);

        String json = redis.opsForValue().get(key);
        assertThat(json)
                .as("「不存在」这个结论本身也要缓存。不缓存的话，"
                        + "攻击者用随机 ID 循环请求就能让每一次都打到数据库")
                .isNotNull();
        assertThat(JsonUtil.parse(json, CacheWrapper.class).nullValue()).isTrue();

        // 再读一次仍然是同一个业务错误码，而不是把空值缓存当成"有数据"
        assertThatThrownBy(() -> activityService.detail(ghostId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVITY_NOT_FOUND);
    }

    @Test
    @DisplayName("A5 ID 非正数直接拒绝，连缓存都不查（防穿透第一道）")
    void A5_参数校验前置() {
        assertThatThrownBy(() -> activityService.detail(-1))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVITY_NOT_FOUND);
        assertThat(redis.hasKey(RedisKeys.activityDetail(-1L)))
                .as("非法 ID 不该在 Redis 里留下任何 key，"
                        + "否则用随机负数就能把内存刷满")
                .isFalse();
    }

    @Test
    @DisplayName("A6 逻辑过期 → 立即返回旧值并在后台重建，请求不等回源")
    void A6_逻辑过期后台重建() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        String key = RedisKeys.activityDetail(act.activityId());

        String oldName = activityService.detail(act.activityId()).getName();
        jdbc.update("UPDATE t_seckill_activity SET name = ? WHERE id = ?",
                "重建后的名字", act.activityId());

        // 手动把逻辑过期时刻推到过去，物理 key 仍然在（物理 TTL 更长）
        expireLogically(key);

        assertThat(activityService.detail(act.activityId()).getName())
                .as("逻辑过期的第一个请求应当立刻拿到旧值。"
                        + "让它同步回源就回到了互斥重建的老路：热点 key 上"
                        + "几千个 Tomcat 线程一起等同一次数据库查询")
                .isEqualTo(oldName);

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(
                        activityService.detail(act.activityId()).getName())
                        .as("后台单飞重建应当在短时间内把缓存刷成新值")
                        .isEqualTo("重建后的名字"));
    }

    @Test
    @DisplayName("A7 关闭活动 → 缓存失效，下次读到已关闭状态")
    void A7_管理操作使缓存失效() {
        TestFixture.Activity act = fixture.createRunningActivity(10);

        assertThat(activityService.detail(act.activityId()).getStatus())
                .isEqualTo(ActivityStatus.RUNNING.code());

        activityService.close(act.activityId(), act.adminId());

        assertThat(activityService.detail(act.activityId()).getStatus())
                .as("CLOSED 是管理员的显式决定，无法从时间窗口推导出来，"
                        + "所以关闭操作必须主动清缓存。"
                        + "不清的话用户在接下来两小时里看到的还是「进行中」")
                .isEqualTo(ActivityStatus.CLOSED.code());
    }

    @Test
    @DisplayName("A8 活动状态由时间窗口推导，不需要为自动流转清缓存")
    void A8_状态按时间推导() {
        TestFixture.Activity act = fixture.createNotStartedActivity(10);

        assertThat(activityService.detail(act.activityId()).getStatus())
                .as("fixture 把 DB 状态写成了 RUNNING，但开始时间在 1 小时后。"
                        + "展示状态取自时间窗口，所以应当是「待开始」")
                .isEqualTo(ActivityStatus.READY.code());

        // 把时间窗口挪到过去，缓存不动
        jdbc.update("UPDATE t_seckill_activity SET start_time = ?, end_time = ? WHERE id = ?",
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1),
                act.activityId());
        redis.delete(RedisKeys.activityDetail(act.activityId()));

        assertThat(activityService.detail(act.activityId()).getStatus())
                .isEqualTo(ActivityStatus.ENDED.code());
    }

    @Test
    @DisplayName("A9 缓存内容损坏 → 删除重建，不是一直报错")
    void A9_脏缓存自愈() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        String key = RedisKeys.activityDetail(act.activityId());

        redis.opsForValue().set(key, "{这不是合法 JSON");

        ActivityDetailVO vo = activityService.detail(act.activityId());
        assertThat(vo.getActivityId())
                .as("历史格式残留、有人手改过缓存都会造成这种情况。"
                        + "解析失败就删掉重建，不能让一个坏 key 把接口卡死到 TTL 到期")
                .isEqualTo(act.activityId());
    }

    // ------------------------------------------------------------------

    private int remainStock(TestFixture.Activity act) {
        return activityService.detail(act.activityId()).getGoodsList().get(0).getRemainStock();
    }

    /** 把缓存的逻辑过期时刻改到过去，保留数据与物理 TTL */
    private void expireLogically(String key) {
        CacheWrapper w = JsonUtil.parse(redis.opsForValue().get(key), CacheWrapper.class);
        w.setExpireAt(System.currentTimeMillis() - 1);
        Long ttl = redis.getExpire(key);
        redis.opsForValue().set(key, JsonUtil.toJson(w),
                Duration.ofSeconds(ttl == null || ttl <= 0 ? 600 : ttl));
    }
}

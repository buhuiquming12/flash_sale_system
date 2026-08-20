package com.fss.biz.activity.service;

import com.fss.common.enums.ActivityStatus;
import com.fss.common.enums.WarmupState;
import com.fss.common.error.Assert;
import com.fss.common.error.ErrorCode;
import com.fss.domain.entity.SeckillActivity;
import com.fss.domain.entity.SeckillGoods;
import com.fss.domain.mapper.SeckillActivityMapper;
import com.fss.domain.mapper.SeckillGoodsMapper;
import com.fss.infra.config.FssProperties;
import com.fss.infra.lock.LockService;
import com.fss.infra.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 活动预热：把库存和活动元数据从 MySQL 搬进 Redis。
 *
 * <p>预热是秒杀链路能成立的前提——没有预热，Lua 脚本读不到 {@code seckill:goods}，
 * 所有请求返回"未预热"。所以：
 * <ul>
 *   <li>活动 {@code READY → RUNNING} 的推进条件里带 {@code warmup_state = 2}，
 *       预热没完成的活动<b>不会</b>进入进行中（故障用例 F13）。用户看到
 *       "活动即将开始"，比看到系统错误好得多。</li>
 *   <li>预热失败会把 {@code warmup_state} 置为 FAILED 并告警，等人介入。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarmupService {

    private final SeckillActivityMapper activityMapper;
    private final SeckillGoodsMapper    goodsMapper;
    private final StringRedisTemplate   redis;
    private final LockService           lockService;
    private final FssProperties         props;

    /**
     * 预热一个活动，<b>可重复执行</b>。
     *
     * <p>加分布式锁只是为了减少无谓的重复工作；正确性完全由 {@code setIfAbsent} 保证——
     * 就算锁失效、两个实例同时预热，结果也是对的。
     */
    public void warmupOne(long activityId) {
        boolean ran = lockService.tryRun(RedisKeys.warmupLock(activityId), 120,
                () -> doWarmup(activityId));
        if (!ran) {
            log.info("stage=WARMUP activityId={} result=SKIP 其他实例正在预热", activityId);
        }
    }

    private void doWarmup(long activityId) {
        SeckillActivity act = activityMapper.selectById(activityId);
        Assert.requireFound(act, ErrorCode.ACTIVITY_NOT_FOUND);

        // 允许 RUNNING 是有意的：活动进行中重新预热是合法的运维动作
        // （元数据被误改、Redis 主从切换后需要补数据）。正是这种场景让
        // setIfAbsent 变得不可或缺——库存绝不能跟着元数据一起被重置
        Assert.require(act.getStatus() == ActivityStatus.READY.code()
                        || act.getStatus() == ActivityStatus.RUNNING.code(),
                ErrorCode.ACTIVITY_STATUS_ILLEGAL,
                "只有待开始或进行中的活动可以预热，当前状态: "
                        + ActivityStatus.of(act.getStatus()).getDesc());

        List<SeckillGoods> goodsList = goodsMapper.selectByActivity(activityId);
        Assert.require(!goodsList.isEmpty(), ErrorCode.ACTIVITY_NOT_READY, "活动无商品，无法预热");

        Duration ttl = keyTtl(act.getEndTime());
        for (SeckillGoods g : goodsList) {
            String goodsKey = RedisKeys.goods(activityId, g.getSkuId());
            String stockKey = RedisKeys.stock(activityId, g.getSkuId());

            // 元数据可以无条件覆盖：它是活动配置的投影，DB 才是权威
            Map<String, String> meta = new HashMap<>();
            meta.put("status",       String.valueOf(redisStatus(g)));
            meta.put("startTime",    String.valueOf(toMillis(act.getStartTime())));
            meta.put("endTime",      String.valueOf(toMillis(act.getEndTime())));
            meta.put("price",        g.getSeckillPrice().toPlainString());
            meta.put("limitPerUser", String.valueOf(g.getLimitPerUser()));
            meta.put("totalStock",   String.valueOf(g.getTotalStock()));
            meta.put("version",      String.valueOf(act.getWarmupVersion() + 1));
            redis.opsForHash().putAll(goodsKey, meta);
            redis.expire(goodsKey, ttl);

            // 库存只在 key 不存在时初始化。
            //
            // *** setIfAbsent 而不是 set，是预热幂等的全部秘密。 ***
            // 用 set 的话，活动进行中重跑一次预热（人为误操作、任务重复触发、
            // 或者仅仅是 job 实例重启后补跑一轮），Redis 库存就被重置成 DB 的
            // available_stock —— 而此时已经有 N 个用户拿着资格在排队落库，
            // 他们扣的那 N 份库存凭空又回来了，直接超卖 N 件。
            Boolean created = redis.opsForValue()
                    .setIfAbsent(stockKey, String.valueOf(g.getAvailableStock()), ttl);
            if (Boolean.FALSE.equals(created)) {
                log.warn("stage=WARMUP activityId={} skuId={} 库存已存在，跳过初始化 "
                                + "redisStock={} dbAvailable={}",
                        activityId, g.getSkuId(), redis.opsForValue().get(stockKey),
                        g.getAvailableStock());
            } else {
                log.info("stage=WARMUP activityId={} skuId={} stock={} ttl={}",
                        activityId, g.getSkuId(), g.getAvailableStock(), ttl);
            }
        }

        activityMapper.markWarmupDone(activityId);
        log.info("stage=WARMUP activityId={} goodsCount={} result=OK version={}",
                activityId, goodsList.size(), act.getWarmupVersion() + 1);
    }

    /** 预热失败的落库标记，由调用方在捕获异常后调用 */
    public void markFailed(long activityId) {
        activityMapper.updateWarmupState(activityId, WarmupState.FAILED.code());
    }

    /**
     * Redis 侧的商品状态。
     *
     * <p>DB 的 {@code status = 2}（售罄）不往 Redis 搬：Redis 库存是否为 0 由
     * Redis 自己说话，搬一个可能已经过时的售罄标记进来，会让刚回补过库存的商品
     * 被错误地快速失败掉。停售(0) 必须搬——那是管理员的显式决定。
     */
    private int redisStatus(SeckillGoods g) {
        return g.getStatus() == SeckillGoods.STATUS_OFF_SALE
                ? SeckillGoods.STATUS_OFF_SALE
                : SeckillGoods.STATUS_ON_SALE;
    }

    /**
     * key 的存活时间 = 距活动结束 + 缓冲。
     *
     * <p><b>必须有下限。</b> 对一个已经结束的活动执行预热（人工排查、补数据）时，
     * {@code endTime - now} 是负数，直接拿去 {@code EXPIRE} 会让 Redis 报
     * "invalid expire time"，整个预热在第一个商品上就炸掉。
     */
    private Duration keyTtl(LocalDateTime endTime) {
        Duration buffer = props.getSeckill().getKeyTtlAfterEnd();
        Duration until = Duration.between(LocalDateTime.now(), endTime);
        Duration ttl = until.isNegative() ? buffer : until.plus(buffer);
        return ttl.compareTo(buffer) < 0 ? buffer : ttl;
    }

    private long toMillis(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}

package com.fss.biz.seckill.core;

import com.fss.common.enums.SeckillRequestStatus;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.infra.config.FssProperties;
import com.fss.infra.config.SentinelConfig;
import com.fss.infra.metrics.SeckillMetrics;
import com.fss.infra.redis.RedisKeys;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Redis 侧秒杀原语。所有 Lua 调用都从这里出去。
 *
 * <p><b>失败方向：fail-closed。</b> 与限流器相反——{@link #trySeckill} 一旦
 * 抛异常（超时、连接失败），绝不能当成"通过"继续往下走，那就是超卖；
 * 也不能简单当成"失败"，因为此时<b>无法确定脚本到底执行了没有</b>：
 * 请求可能已经在 Redis 侧扣掉了库存，只是响应没回来。
 *
 * <p>阶段二对不确定结果的处理是"对用户报系统繁忙 + 打一条可检索的日志"，
 * 库存差额由阶段四的库存对账任务收敛（Redis 少扣或多扣都能查出来）。
 * 完整的 {@code checkUncertain}（记录不确定请求、事后按 requestNo 反查 Redis
 * 确认是否已扣）留在阶段四，因为它需要 {@code t_reconcile_task} 真正跑起来。
 * <p>阶段四补上了完整的不确定结果处理：{@link #trySeckill} 遇到 Redis 异常时抛
 * {@link UncertainResultException}，调用方把这条请求登记进
 * {@link UncertainRecorder}，由 {@code UncertainCheckJob} 事后用
 * {@link #requestExists} 判定真实结果——脚本 A 的原子性保证
 * <b>{@code req key} 存在 ⟺ 库存已扣</b>，这条等价关系就是判据。
 */
@Slf4j
@Component
public class SeckillExecutor {

    private final StringRedisTemplate redis;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List>   seckillScript;
    private final RedisScript<Long>   rollbackScript;
    private final RedisScript<Long>   releaseScript;
    private final RedisScript<Long>   writeResultScript;
    private final FssProperties       props;
    private final SeckillMetrics      metrics;

    @SuppressWarnings("rawtypes")
    public SeckillExecutor(StringRedisTemplate redis,
                           @Qualifier("seckillScript") RedisScript<List> seckillScript,
                           @Qualifier("rollbackScript") RedisScript<Long> rollbackScript,
                           @Qualifier("releaseScript") RedisScript<Long> releaseScript,
                           @Qualifier("writeResultScript") RedisScript<Long> writeResultScript,
                           FssProperties props,
                           SeckillMetrics metrics) {
        this.redis = redis;
        this.seckillScript = seckillScript;
        this.rollbackScript = rollbackScript;
        this.releaseScript = releaseScript;
        this.writeResultScript = writeResultScript;
        this.props = props;
        this.metrics = metrics;
    }

    /**
     * 脚本 A：资格判定与预扣。整个系统唯一的资格分配入口。
     *
     * <p>用并发线程数限流保护 Redis：Redis 变慢时线程堆积在等响应上，
     * 阈值一到就快速失败，而不是让 Tomcat 线程池被拖死。
     *
     * @throws UncertainResultException Redis 调用失败，<b>无法确定脚本是否已执行</b>
     */
    @SentinelResource(value = SentinelConfig.RES_SECKILL_SCRIPT,
            blockHandler = "onScriptBlocked",
            blockHandlerClass = SeckillExecutor.class)
    public SeckillOutcome trySeckill(long activityId, long skuId, long userId,
                                     int qty, String requestNo, String traceId) {
        long t0 = System.nanoTime();
        List<String> keys = List.of(
                RedisKeys.goods(activityId, skuId),
                RedisKeys.stock(activityId, skuId),
                RedisKeys.bought(activityId, skuId),
                RedisKeys.request(activityId, skuId, requestNo));

        List<?> raw;
        try {
            raw = redis.execute(seckillScript, keys,
                    String.valueOf(userId), String.valueOf(qty), requestNo,
                    traceId == null ? "" : traceId,
                    String.valueOf(props.getSeckill().getResultTtl().toSeconds()));
        } catch (Exception e) {
            // 关键：这里既不能放行也不能简单判失败——脚本可能已经扣了库存。
            // 抛专用异常让调用方把它登记进"待确认"，由定时任务事后判定
            metrics.redisUncertain("seckill");
            log.error("stage=SECKILL_LUA_UNCERTAIN requestNo={} userId={} activityId={} skuId={} "
                            + "cost={}ms 无法确定脚本是否已执行，登记待确认",
                    requestNo, userId, activityId, skuId, millis(t0), e);
            throw new UncertainResultException(requestNo, e);
        } finally {
            metrics.luaTimer("seckill", System.nanoTime() - t0);
        }
        if (raw == null || raw.isEmpty()) {
            log.error("stage=SECKILL_LUA requestNo={} result=EMPTY 脚本返回空", requestNo);
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }

        int  code   = ((Number) raw.get(0)).intValue();
        long remain = raw.size() > 1 && raw.get(1) != null ? ((Number) raw.get(1)).longValue() : 0L;
        log.info("stage=SECKILL_LUA requestNo={} userId={} activityId={} skuId={} code={} remain={} cost={}ms",
                requestNo, userId, activityId, skuId, code, remain, millis(t0));
        return new SeckillOutcome(code, remain);
    }

    /** Sentinel 并发线程数超限。签名必须与被保护方法一致并在末尾加 BlockException */
    public static SeckillOutcome onScriptBlocked(long activityId, long skuId, long userId,
                                                 int qty, String requestNo, String traceId,
                                                 BlockException e) {
        log.warn("stage=SECKILL_LUA requestNo={} result=BLOCKED Redis 并发线程数超阈值", requestNo);
        throw new BizException(ErrorCode.SYSTEM_BUSY, "活动太火爆，请稍后再试");
    }

    /**
     * 脚本 B：补偿回补（ROLLBACK）。库存归还，资格按 keepBought 决定。
     *
     * @param keepBought true 时<b>不</b>归还用户购买资格。落库失败原因是
     *                   {@code ALREADY_BOUGHT} 时必须传 true，否则用户重抢 →
     *                   DB 又冲突 → 又回补，形成死循环
     * @param failStatus 写入请求记录的终态。确定性失败写具体码，只有系统原因才写
     *                   {@code COMPENSATED} —— 设计文档一律写 5，而消费端同时会往
     *                   {@code t_seckill_request} 落一条具体码，同一个请求在 Redis
     *                   和 DB 里就有了两个不同的结论
     * @return 见 {@link RollbackOutcome}。调用方<b>必须</b>区分
     *         {@code ALREADY_DONE}（重复投递，正常）与 {@code FAILED}（库存可能泄漏，要告警）
     */
    public RollbackOutcome rollback(long activityId, long skuId, long userId, int qty,
                                    String requestNo, String reason, boolean keepBought,
                                    SeckillRequestStatus failStatus) {
        List<String> keys = List.of(
                RedisKeys.stock(activityId, skuId),
                RedisKeys.bought(activityId, skuId),
                RedisKeys.request(activityId, skuId, requestNo),
                RedisKeys.goods(activityId, skuId));
        long t0 = System.nanoTime();
        try {
            Long r = redis.execute(rollbackScript, keys,
                    String.valueOf(userId), String.valueOf(qty),
                    reason == null ? "" : reason,
                    keepBought ? "1" : "0",
                    String.valueOf(props.getSeckill().getResultTtl().toSeconds()),
                    String.valueOf(failStatus.code()));
            RollbackOutcome outcome = toOutcome(r);
            if (outcome == RollbackOutcome.DONE) {
                metrics.stockRollback(activityId, skuId, "compensate");
            }
            log.info("stage=STOCK_ROLLBACK requestNo={} userId={} qty={} keepBought={} "
                            + "failStatus={} outcome={} reason={}",
                    requestNo, userId, qty, keepBought, failStatus, outcome, reason);
            return outcome;
        } catch (Exception e) {
            // 回补失败是库存泄漏（少卖），不是超卖。方向安全，但必须能被告警发现
            metrics.redisUncertain("rollback");
            log.error("stage=STOCK_ROLLBACK requestNo={} result=ERROR 库存暂时泄漏，待对账修正",
                    requestNo, e);
            return RollbackOutcome.FAILED;
        } finally {
            metrics.luaTimer("rollback", System.nanoTime() - t0);
        }
    }

    /**
     * 脚本 C：取消回补（RELEASE）。只归还库存，保留用户购买标记（决策 1）。
     *
     * @return 见 {@link RollbackOutcome}
     */
    public RollbackOutcome release(long activityId, long skuId, String orderNo, int qty) {
        List<String> keys = List.of(
                RedisKeys.stock(activityId, skuId),
                RedisKeys.released(activityId, skuId),
                RedisKeys.goods(activityId, skuId));
        long t0 = System.nanoTime();
        try {
            Long r = redis.execute(releaseScript, keys, orderNo, String.valueOf(qty),
                    String.valueOf(props.getSeckill().getReleasedTtl().toSeconds()));
            RollbackOutcome outcome = toOutcome(r);
            if (outcome == RollbackOutcome.DONE) {
                metrics.stockRollback(activityId, skuId, "release");
            }
            log.info("stage=STOCK_RELEASE_REDIS orderNo={} qty={} outcome={}", orderNo, qty, outcome);
            return outcome;
        } catch (Exception e) {
            metrics.redisUncertain("release");
            log.error("stage=STOCK_RELEASE_REDIS orderNo={} result=ERROR 库存暂时泄漏，待对账修正",
                    orderNo, e);
            return RollbackOutcome.FAILED;
        } finally {
            metrics.luaTimer("release", System.nanoTime() - t0);
        }
    }

    /**
     * 脚本返回码 → 结果。
     *
     * <p>{@code 0} 是"本次真的回补了"，{@code 1} 是"幂等命中，无需动作"，
     * 其余（含 {@code null}）一律当作失败——<b>未知按失败处理</b>：
     * 多报一次警的代价是一条日志，漏报一次的代价是库存静默少卖。
     *
     * <p>包级可见是为了能被单测直接驱动：这条映射是整个告警分级的地基，
     * 一个方向改错就会把"库存泄漏"降级成"正常的重复投递"。
     */
    static RollbackOutcome toOutcome(Long r) {
        if (Long.valueOf(0L).equals(r)) {
            return RollbackOutcome.DONE;
        }
        if (Long.valueOf(1L).equals(r)) {
            return RollbackOutcome.ALREADY_DONE;
        }
        return RollbackOutcome.FAILED;
    }

    /**
     * 脚本 D：写入请求结论，供客户端轮询。终态不会被覆盖。
     *
     * <p>{@code userId} 必须传：查询接口读到结论后要做归属校验，
     * 被 Lua 提前拒绝的请求没有创建过 req key，这个字段只能在这里补上。
     */
    public void writeResult(long activityId, long skuId, String requestNo, long userId,
                            SeckillRequestStatus status, String orderNo, String reason) {
        try {
            redis.execute(writeResultScript,
                    List.of(RedisKeys.request(activityId, skuId, requestNo)),
                    String.valueOf(status.code()),
                    orderNo == null ? "" : orderNo,
                    reason == null ? "" : reason,
                    String.valueOf(props.getSeckill().getResultTtl().toSeconds()),
                    String.valueOf(userId));
        } catch (Exception e) {
            // 结果写不进去不影响正确性：查询接口在 Redis 未命中时会回查数据库，
            // 那才是结论的权威来源
            log.warn("stage=SECKILL_RESULT_WRITE requestNo={} result=ERROR，查询将回查 DB",
                    requestNo, e);
        }
    }

    /**
     * 读请求结论。
     *
     * @return null 表示 Redis 里没有（已过期或从未写入），调用方须回查数据库
     */
    public RequestResult readResult(long activityId, long skuId, String requestNo) {
        try {
            Map<Object, Object> h = redis.opsForHash()
                    .entries(RedisKeys.request(activityId, skuId, requestNo));
            if (h.isEmpty()) {
                return null;
            }
            return new RequestResult(
                    Integer.parseInt(String.valueOf(h.getOrDefault("status", "0"))),
                    str(h.get("userId")), str(h.get("orderNo")), str(h.get("reason")));
        } catch (Exception e) {
            log.warn("stage=SECKILL_RESULT_READ requestNo={} result=ERROR，回查 DB", requestNo, e);
            return null;
        }
    }

    /** Redis 侧剩余库存。对账与监控用；查不到返回 null（未预热） */
    public Long currentStock(long activityId, long skuId) {
        String v = redis.opsForValue().get(RedisKeys.stock(activityId, skuId));
        return v == null ? null : Long.parseLong(v);
    }

    /**
     * 请求记录是否存在。
     *
     * <p><b>不确定结果处理的唯一判据。</b> 脚本 A 的原子性保证"预扣库存"与
     * "写 req key"要么都发生要么都不发生，所以
     * {@code req key} 存在 ⟺ 库存已扣。存在则补发消息，不存在则脚本没执行过、
     * 库存没动、什么都不用做。
     *
     * <p><b>读失败必须抛而不是返回 false。</b> 返回 false 会被判成"脚本没执行"，
     * 于是一份已扣的库存永远没人补消息也没人回补——把一个"暂时读不到"
     * 变成了一个永久泄漏。抛出去则本轮跳过，记录留在待确认集合里下轮再试。
     */
    public boolean requestExists(long activityId, long skuId, String requestNo) {
        return Boolean.TRUE.equals(
                redis.hasKey(RedisKeys.request(activityId, skuId, requestNo)));
    }

    /**
     * Redis 调用结果不确定。
     *
     * <p>刻意<b>不</b>继承 {@code BizException}：调用方必须显式处理它
     * （登记待确认），而不是当成一个普通的业务错误码放过去。
     * 它也因此会被 Sentinel 计入异常统计——Redis 超时确实是故障，
     * 而"库存不足"不是。
     */
    public static class UncertainResultException extends RuntimeException {
        private final String requestNo;

        public UncertainResultException(String requestNo, Throwable cause) {
            super("Redis 调用结果不确定: " + requestNo, cause);
            this.requestNo = requestNo;
        }

        public String getRequestNo() {
            return requestNo;
        }
    }

    /**
     * @param userId 字符串形式。Redis Hash 里存的就是字符串，
     *               在这里解析成 long 意味着脏数据会变成 NumberFormatException，
     *               而这只是一个用来做归属校验的值，比对字符串就够了
     */
    public record RequestResult(int status, String userId, String orderNo, String reason) {
    }

    private static String str(Object o) {
        String s = o == null ? null : String.valueOf(o);
        return s == null || s.isEmpty() ? null : s;
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}

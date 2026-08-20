package com.fss.infra.cache;

import com.fss.common.trace.TraceContext;
import com.fss.common.util.JsonUtil;
import com.fss.infra.config.FssProperties;
import com.fss.infra.lock.LockService;
import com.fss.infra.redis.RedisKeys;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 逻辑过期 + 单飞重建 + 空值缓存 + TTL 抖动。
 *
 * <p>三类缓存问题在这里被一次性处理掉：
 * <table>
 *   <tr><th>问题</th><th>手段</th></tr>
 *   <tr><td>穿透：查不存在的数据</td><td>空值缓存（{@code data = null}，短 TTL）</td></tr>
 *   <tr><td>击穿：热点 key 过期</td><td>逻辑过期 + 单飞重建，请求永不等回源</td></tr>
 *   <tr><td>雪崩：大量 key 同时过期</td><td>物理 TTL 加随机抖动</td></tr>
 * </table>
 *
 * <p><b>逻辑过期为什么比互斥重建好</b>：互斥重建（拿到锁的回源，没拿到的等待重试）
 * 在回源耗时 200ms 时，会让这 200ms 内的全部请求都阻塞——热点 key 上就是几千个
 * Tomcat 线程同时 sleep，线程池瞬间打满。逻辑过期让它们立刻拿到"稍旧"的数据，
 * 只有一个后台线程去回源。代价是数据最多旧一个重建周期，对活动详情这种
 * 场景（库存展示本来就允许秒级延迟）完全可以接受。
 */
@Slf4j
@Component
public class LogicalExpiryCache {

    private final StringRedisTemplate redis;
    private final LockService         lockService;
    private final FssProperties       props;

    /**
     * 缓存重建线程池。
     *
     * <p><b>拒绝策略是 DiscardPolicy 而不是 CallerRunsPolicy。</b>
     * CallerRuns 会把回源塞回请求线程执行，正好毁掉逻辑过期"请求永不等回源"的全部意义；
     * 队列满意味着已经有一堆重建在排队，丢掉这一次没有任何损失——
     * 下一个读到逻辑过期的请求会再触发一次。
     */
    private final ThreadPoolExecutor rebuildPool = new ThreadPoolExecutor(
            2, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100),
            r -> {
                Thread t = new Thread(r, "cache-rebuild-" + System.nanoTime() % 1000);
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    public LogicalExpiryCache(StringRedisTemplate redis, LockService lockService,
                              FssProperties props) {
        this.redis = redis;
        this.lockService = lockService;
        this.props = props;
    }

    /**
     * 读缓存，未命中或逻辑过期时按需回源。
     *
     * @param loader 回源函数，返回 {@code null} 表示数据确实不存在（写入空值缓存）
     * @return 业务对象；{@code null} 表示数据不存在（调用方据此抛业务异常）
     */
    public <T> T get(String key, Class<T> type, Supplier<T> loader) {
        String json;
        try {
            json = redis.opsForValue().get(key);
        } catch (Exception e) {
            // 缓存读失败必须 fail-open 回源：否则 Redis 一抖动，
            // 整个详情接口跟着不可用——把缓存故障放大成业务故障是最不划算的失败方式
            log.warn("缓存读取失败，直接回源 key={}", key, e);
            return loader.get();
        }

        if (json == null) {
            return loadAndCache(key, loader);
        }

        CacheWrapper w;
        try {
            w = JsonUtil.parse(json, CacheWrapper.class);
        } catch (Exception e) {
            // 缓存里是脏数据（历史格式、被人手改过）。删掉重建，不要一直报错
            log.warn("缓存内容无法解析，删除并重建 key={}", key, e);
            redis.delete(key);
            return loadAndCache(key, loader);
        }

        if (w.nullValue()) {
            return null;                         // 空值缓存命中，防穿透
        }
        T data = JsonUtil.parse(w.getData(), type);

        if (w.logicallyExpired()) {
            asyncRebuild(key, loader);
            log.debug("stage=CACHE key={} result=STALE 已触发后台重建", key);
        }
        return data;
    }

    /** 主动失效：写操作后调用，让下一次读回源 */
    public void evict(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("缓存删除失败 key={}，将等待 TTL 自然过期", key, e);
        }
    }

    // ------------------------------------------------------------------

    private <T> T loadAndCache(String key, Supplier<T> loader) {
        T value = loader.get();
        write(key, value);
        return value;
    }

    /**
     * 后台单飞重建。
     *
     * <p>锁在<b>异步任务内部</b>获取，而不是提交前：提交前拿锁的话，
     * 一旦线程池队列已满、任务被 DiscardPolicy 丢弃，{@code finally} 里的
     * unlock 就永远不会执行，锁要等到租期结束才释放——期间所有重建都被挡住，
     * 缓存一直是旧值。
     */
    private <T> void asyncRebuild(String key, Supplier<T> loader) {
        // 显式声明成 Runnable：TraceContext 同时有 wrap(Runnable) 和 wrap(Callable)，
        // 直接传 lambda 会因为两者都匹配而编译不过
        Runnable task = () -> lockService.trySupply(RedisKeys.cacheRebuildLock(key), 30, () -> {
            try {
                write(key, loader.get());
                log.debug("stage=CACHE_REBUILD key={} result=OK", key);
            } catch (Exception e) {
                log.error("stage=CACHE_REBUILD key={} result=ERROR", key, e);
            }
            return Boolean.TRUE;
        });
        rebuildPool.execute(TraceContext.wrap(task));
    }

    private void write(String key, Object value) {
        Duration logical = value == null
                ? props.getSeckill().getNullCacheTtl()
                : props.getSeckill().getCacheTtl();

        CacheWrapper w = new CacheWrapper(
                value == null ? null : JsonUtil.toJson(value),
                System.currentTimeMillis() + logical.toMillis());

        // 物理 TTL = 逻辑过期 + 缓冲，保证逻辑过期之后旧值还在，重建期间有东西可返回
        Duration physical = logical
                .plus(props.getSeckill().getCachePhysicalBuffer())
                .plusSeconds(jitterSeconds());
        try {
            redis.opsForValue().set(key, JsonUtil.toJson(w), physical);
        } catch (Exception e) {
            log.warn("缓存写入失败 key={}，本次仅返回回源结果", key, e);
        }
    }

    /**
     * TTL 抖动，防雪崩。
     *
     * <p>预热时一批 key 同时写入，如果 TTL 完全相同，它们会在同一秒集体过期，
     * 回源压力瞬间打到数据库。加一个 0~jitter 的随机量把过期时刻摊开。
     */
    private long jitterSeconds() {
        long max = props.getSeckill().getCacheTtlJitter().toSeconds();
        return max <= 0 ? 0 : ThreadLocalRandom.current().nextLong(max);
    }

    @PreDestroy
    void shutdown() {
        rebuildPool.shutdownNow();
    }
}

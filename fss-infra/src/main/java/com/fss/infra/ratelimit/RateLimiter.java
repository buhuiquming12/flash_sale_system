package com.fss.infra.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 令牌桶限流（四级限流的第三级）。
 *
 * <p><b>失败方向：fail-open。</b> 限流组件自己出问题时放行，而不是拦截。
 * 理由是限流不是正确性的一部分——后面还有 Sentinel 和 Lua 兜着，
 * 放行最坏是多打一些流量进来；而拦截则是把"限流器故障"直接升级成"业务不可用"。
 *
 * <p>这一点必须和库存判定<b>反过来</b>：库存判定的 Redis 挂了绝不能放行，
 * 放行就是超卖，那是真实的资损。同一套系统里两个 Redis 调用、
 * 两个相反的失败方向，是这套设计里最容易搞反的地方。
 */
@Slf4j
@Component
public class RateLimiter {

    private final StringRedisTemplate redis;
    private final RedisScript<Long>   tokenBucket;

    public RateLimiter(StringRedisTemplate redis,
                       @Qualifier("tokenBucketScript") RedisScript<Long> tokenBucket) {
        this.redis = redis;
        this.tokenBucket = tokenBucket;
    }

    /**
     * @param capacity 桶容量，决定允许多大的瞬时突发
     * @param rate     每秒填充速率，决定长期平均放行速率
     * @return true 放行
     */
    public boolean tryAcquire(String key, int capacity, int rate) {
        return tryAcquire(key, capacity, rate, 1);
    }

    public boolean tryAcquire(String key, int capacity, int rate, int permits) {
        if (rate <= 0) {
            return true;                         // 速率 0 视为不限流，便于压测时整段关掉
        }
        try {
            Long allowed = redis.execute(tokenBucket, List.of(key),
                    String.valueOf(capacity), String.valueOf(rate), String.valueOf(permits));
            return !Long.valueOf(0L).equals(allowed);
        } catch (Exception e) {
            log.warn("限流检查失败，放行 key={}", key, e);
            return true;
        }
    }
}

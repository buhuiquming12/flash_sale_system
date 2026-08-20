package com.fss.ratelimit;

import com.fss.infra.ratelimit.RateLimiter;
import com.fss.test.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 令牌桶限流验收（四级限流的第三级）。
 *
 * <p>这里测的是限流<b>机制</b>本身。HTTP 层真的返回 429、以及各接口的档位划分，
 * 见 {@code RateLimitWebTest}。两层都要测：机制对了但没挂上拦截器，
 * 或者挂上了但阈值配错，都是完全静默的失败。
 */
class RateLimitTest extends IntegrationTestBase {

    @Autowired RateLimiter rateLimiter;

    @Test
    @DisplayName("L1 单用户 100 次连续请求 → 只放行约等于桶容量的次数，其余全部拒绝")
    void L1_突发被限住() {
        String key = key();
        int capacity = 4;
        int rate = 2;

        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (rateLimiter.tryAcquire(key, capacity, rate)) {
                allowed++;
            }
        }

        // 100 次几乎是瞬间打完的，期间最多补进来一两个令牌
        assertThat(allowed)
                .as("放行数应接近桶容量 %d。远大于它说明令牌桶在漏 —— "
                        + "最常见的原因是 ts 被存成整数丢了小数位，"
                        + "于是每次调用算出的 delta 都多出将近 1 秒的令牌", capacity)
                .isBetween(capacity, capacity + 2);
        assertThat(100 - allowed)
                .as("绝大多数请求必须被拒绝，对应 HTTP 429")
                .isGreaterThanOrEqualTo(94);
    }

    @Test
    @DisplayName("L2 令牌按速率匀速补充，不是等窗口切换时一次性补满")
    void L2_匀速补充() throws Exception {
        String key = key();
        int capacity = 10;
        int rate = 10;

        while (rateLimiter.tryAcquire(key, capacity, rate)) {
            // 先把桶抽干
        }
        assertThat(rateLimiter.tryAcquire(key, capacity, rate)).isFalse();

        Thread.sleep(500);
        int afterHalfSecond = 0;
        while (rateLimiter.tryAcquire(key, capacity, rate)) {
            afterHalfSecond++;
        }

        // 500ms × 10/s ≈ 5 个令牌。固定窗口计数器在这里会给出 0（窗口未切换）
        // 或者 10（窗口刚切换），两者都不是"匀速"
        assertThat(afterHalfSecond)
                .as("半秒应补进约 %d 个令牌，实际 %d", rate / 2, afterHalfSecond)
                .isBetween(3, 8);
    }

    @Test
    @DisplayName("L3 桶按 key 隔离：一个用户被限速不影响另一个")
    void L3_按key隔离() {
        String a = key();
        String b = key();
        int capacity = 2;
        int rate = 1;

        while (rateLimiter.tryAcquire(a, capacity, rate)) {
            // 抽干 a
        }
        assertThat(rateLimiter.tryAcquire(a, capacity, rate)).isFalse();
        assertThat(rateLimiter.tryAcquire(b, capacity, rate))
                .as("限流粒度错了会造成大面积误伤：一个刷接口的用户"
                        + "把所有人的配额吃光，看起来就像整站挂了")
                .isTrue();
    }

    @Test
    @DisplayName("L4 速率配 0 视为不限流，便于压测基线")
    void L4_零速率不限流() {
        String key = key();
        for (int i = 0; i < 50; i++) {
            assertThat(rateLimiter.tryAcquire(key, 0, 0)).isTrue();
        }
    }

    /** 每个用例用独立的 key：容器在多个测试类之间是共享的，复用 key 会互相污染 */
    private String key() {
        return "rate:test:" + UUID.randomUUID();
    }
}

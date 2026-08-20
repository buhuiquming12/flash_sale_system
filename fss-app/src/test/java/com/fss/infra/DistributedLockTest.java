package com.fss.infra;

import com.fss.infra.lock.LockService;
import com.fss.test.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分布式锁验收（对应故障用例 F12：起 3 个 job 实例，每个任务只执行一次）。
 *
 * <p>用线程模拟多实例。这不完全等价于真的起三个进程，但要验的东西一样：
 * 同一把 Redis 锁在并发争抢下只能有一个持有者，且抢不到的一方<b>立刻返回</b>
 * 而不是排队等待——定时任务排队等锁会让调度线程池堆积，一个卡住的任务
 * 能把所有任务拖死。
 */
class DistributedLockTest extends IntegrationTestBase {

    @Autowired LockService lockService;

    @Test
    @DisplayName("K1 10 个线程争同一把锁 → 恰好 1 个执行，其余立即返回 false")
    void K1_只有一个持有者() throws Exception {
        String key = key();
        int threads = 10;
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        boolean ran = lockService.tryRun(key, 30, () -> {
                            executed.incrementAndGet();
                            // 持锁期间睡一下，确保其他线程确实在争抢中
                            try {
                                Thread.sleep(300);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
                        if (!ran) {
                            skipped.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(executed.get()).isEqualTo(1);
        assertThat(skipped.get())
                .as("waitTime = 0，抢不到就跳过。如果这里是 10（全部执行），"
                        + "说明锁根本没生效；如果耗时接近 3 秒，说明变成了排队等锁")
                .isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("K2 锁释放后可以再次获取，不是一次性的")
    void K2_释放后可重新获取() {
        String key = key();
        assertThat(lockService.tryRun(key, 10, () -> { })).isTrue();
        assertThat(lockService.tryRun(key, 10, () -> { }))
                .as("finally 里没 unlock 的话，锁要等 10 秒租期到才释放，"
                        + "定时任务就变成「每 10 秒最多跑一次」")
                .isTrue();
    }

    @Test
    @DisplayName("K3 业务抛异常时锁也要释放，异常原样向上抛")
    void K3_异常不吞不漏锁() {
        String key = key();

        assertThatThrownBy(() -> lockService.tryRun(key, 10, () -> {
            throw new IllegalStateException("业务炸了");
        }))
                .as("吞掉异常会让「任务失败」变成「任务成功但什么都没做」，"
                        + "监控上完全看不出来")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("业务炸了");

        assertThat(lockService.tryRun(key, 10, () -> { }))
                .as("异常路径上也必须解锁")
                .isTrue();
    }

    @Test
    @DisplayName("K4 单飞：拿不到锁的一方得到 null，而不是阻塞等待")
    void K4_单飞语义() throws Exception {
        String key = key();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            pool.submit(() -> lockService.trySupply(key, 30, () -> {
                holding.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "winner";
            }));
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

            long t0 = System.currentTimeMillis();
            String result = lockService.trySupply(key, 30, () -> "loser");
            long cost = System.currentTimeMillis() - t0;

            assertThat(result)
                    .as("缓存重建就靠这个语义：抢不到锁的请求直接返回旧值，"
                            + "绝不能在这里等")
                    .isNull();
            assertThat(cost).as("必须立刻返回，实际耗时 %dms", cost).isLessThan(1000);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private String key() {
        return "lock:test:" + UUID.randomUUID();
    }
}

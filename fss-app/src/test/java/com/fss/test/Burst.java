package com.fss.test;

import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发压测脚手架。
 *
 * <p>用 {@code CountDownLatch} 让所有线程在同一时刻起跑。不加起跑栅栏的话，
 * 线程池是逐个提交任务的，第一个线程可能已经跑完了最后一个线程才开始——
 * 那测的是串行吞吐，不是并发正确性。这个区别很致命：不超卖的 bug 只在
 * 真正的并发下才暴露。
 */
public final class Burst {

    private Burst() {
    }

    /**
     * @param concurrency 请求总数
     * @param poolSize    线程池大小。不必等于 concurrency——1 万个真实线程在
     *                    普通开发机上光是调度就把测试拖垮了，而起跑栅栏已经
     *                    保证了足够的争抢强度
     */
    public static Result run(int concurrency, int poolSize, IntConsumer task) throws Exception {
        Result r = new Result();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, poolSize));

        try {
            for (int i = 0; i < concurrency; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        task.accept(idx);
                        r.success.incrementAndGet();
                    } catch (BizException e) {
                        r.byErrorCode
                                .computeIfAbsent(e.getErrorCode(), k -> new AtomicInteger())
                                .incrementAndGet();
                    } catch (Throwable t) {
                        // 非业务异常一律记录：它们代表实现缺陷，不能被"总数对得上"掩盖
                        r.unexpected.add(t.getClass().getSimpleName() + ": " + t.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(300, TimeUnit.SECONDS))
                    .as("并发请求应在 300s 内全部返回")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
        return r;
    }

    public static Result run(int concurrency, IntConsumer task) throws Exception {
        return run(concurrency, 64, task);
    }

    public static class Result {
        public final AtomicInteger success = new AtomicInteger();
        public final Map<ErrorCode, AtomicInteger> byErrorCode = new ConcurrentHashMap<>();
        /** 必须是并发安全的：ArrayList 在多线程 add 下会丢记录甚至抛数组越界 */
        public final List<String> unexpected = new CopyOnWriteArrayList<>();

        public int count(ErrorCode ec) {
            AtomicInteger c = byErrorCode.get(ec);
            return c == null ? 0 : c.get();
        }

        @Override
        public String toString() {
            return "success=" + success.get() + " byErrorCode=" + byErrorCode
                    + " unexpected=" + unexpected;
        }
    }
}

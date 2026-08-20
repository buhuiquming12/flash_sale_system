package com.fss.infra.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁的编程式入口。
 *
 * <p>注解式（{@link DistributedLock}）适合 key 固定的定时任务；
 * key 需要按参数拼（每个活动一把预热锁、每个缓存 key 一把重建锁）时用这里。
 *
 * <p><b>锁只用来减少无谓竞争，不承担正确性。</b> 这个区分很重要：
 * 预热是幂等的（{@code setIfAbsent}），关单是条件更新，回补有唯一键——
 * 即使锁完全失效、两个实例同时执行，结果也是对的，只是浪费一次执行。
 * 所以这里全部用 {@code waitTime = 0}：抢不到就跳过，绝不排队。
 * 排队等锁会让定时任务线程池堆积，一个卡住的任务能拖垮所有任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LockService {

    private final RedissonClient redisson;

    /**
     * 试着拿锁执行，拿不到直接返回 false，不等待。
     *
     * @param leaseSeconds 租期。给 -1 启用 Redisson 看门狗自动续期；
     *                     给正数则到期强制释放——定时任务用正数更安全，
     *                     进程假死时锁能自己过期，不必等人工介入
     */
    public boolean tryRun(String key, long leaseSeconds, Runnable action) {
        RLock lock = redisson.getLock(key);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, leaseSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("stage=LOCK key={} result=SKIP 其他实例正在执行", key);
                return false;
            }
            action.run();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("stage=LOCK key={} result=INTERRUPTED", key);
            return false;
        } finally {
            // isHeldByCurrentThread 是必需的：业务执行超过 leaseSeconds 时锁已自动过期，
            // 此时 unlock 会抛 IllegalMonitorStateException（Redisson 校验持有者），
            // 那个异常会盖掉业务真正的异常，排查时极具误导性
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 单飞：拿到锁才执行 supplier，拿不到返回 {@code null}。
     *
     * <p>用于缓存重建——同一时刻只让一个线程回源，其余线程拿旧值。
     */
    public <T> T trySupply(String key, long leaseSeconds, Supplier<T> supplier) {
        RLock lock = redisson.getLock(key);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, leaseSeconds, TimeUnit.SECONDS);
            return acquired ? supplier.get() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}

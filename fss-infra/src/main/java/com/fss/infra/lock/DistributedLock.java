package com.fss.infra.lock;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 分布式锁。
 *
 * <p><b>只能加在返回 {@code void} 的方法上</b>——抢不到锁时切面会跳过方法执行，
 * 有返回值的方法此时只能返回 {@code null}，调用方几乎必然把 null 当成"正常的空结果"，
 * 于是"没执行"被误读为"执行了但没结果"。这类静默错误比抛异常危险得多。
 * 切面会在遇到非 void 方法时直接抛异常，而不是好心地返回 null。
 *
 * <p>典型用法是多实例部署下的 {@code @Scheduled}：3 个 job 实例同时到点，
 * 只让一个真正执行。
 *
 * @see LockService 需要按参数拼 key 时用编程式入口
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    /** 锁 key。会被加上 {@code lock:job:} 前缀 */
    String key();

    /**
     * 租期（秒）。
     *
     * <p>必须大于任务的正常执行时间，否则任务还在跑锁就过期了，
     * 另一个实例会同时开跑。但也不能过长：进程被 kill 时锁要等它过期才释放，
     * 期间任务完全停摆。取"正常耗时的 3~5 倍"是个稳妥的起点。
     */
    long leaseSeconds() default 120;
}

package com.fss.infra.lock;

import com.fss.infra.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link DistributedLock} 的切面实现。
 *
 * <p>{@code @Order(0)} 让它排在事务切面（默认 {@code Ordered.LOWEST_PRECEDENCE}）
 * <b>外面</b>：先拿锁再开事务。反过来的话，事务已经开启、数据库连接已经占用，
 * 然后才发现锁被别人拿着——白占一条连接。定时任务批量执行时这会放大成连接池耗尽。
 */
@Slf4j
@Aspect
@Component
@Order(0)
@RequiredArgsConstructor
public class DistributedLockAspect {

    private final LockService lockService;

    @Around("@annotation(lockAnno)")
    public Object around(ProceedingJoinPoint pjp, DistributedLock lockAnno) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        if (method.getReturnType() != void.class) {
            throw new IllegalStateException(
                    "@DistributedLock 只支持 void 方法，否则抢不到锁时只能返回 null "
                            + "而调用方无法区分「没执行」和「执行了但结果为空」: " + method);
        }

        String key = RedisKeys.jobLock(lockAnno.key());
        // pjp.proceed() 的受检异常无法穿过 Runnable，用 AtomicReference 兜出来重新抛出。
        // 吞掉它会让"任务失败"变成"任务成功但什么都没做"
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        boolean executed = lockService.tryRun(key, lockAnno.leaseSeconds(), () -> {
            try {
                pjp.proceed();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });

        if (thrown.get() != null) {
            throw thrown.get();
        }
        if (!executed) {
            log.debug("stage=JOB_SKIP key={} reason=lock_held_by_other_instance", key);
        }
        return null;
    }
}

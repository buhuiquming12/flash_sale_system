package com.fss.infra.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fss.common.trace.TraceContext;

/**
 * 事务提交后回调。
 *
 * <p><b>为什么必须有这个东西</b>：Redis 回写、发消息这类"对外可见"的副作用，
 * 如果在事务内执行而事务随后回滚，外部就会看到一个并不存在的结果——
 * 客户端查到"秒杀成功 + 订单号"，但那张订单被回滚了。
 *
 * <p>提交后回调失败是<b>安全的</b>：结果查询接口在 Redis 未命中时会回查数据库，
 * 能拿到真实结论。所以这里捕获异常只记日志，不向上抛（抛出也无处可去，事务已提交）。
 */
@Slf4j
public final class TxSupport {

    private TxSupport() {
    }

    public static void afterCommit(String name, Runnable action) {
        Runnable traced = TraceContext.wrap(action);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 不在事务中，直接执行
            run(name, traced);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                run(name, traced);
            }
        });
    }

    private static void run(String name, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("提交后回调执行失败 action={}，依赖兜底路径收敛", name, e);
        }
    }
}

package com.fss.biz.job;

import com.fss.biz.reconcile.PaymentReconciler;
import com.fss.biz.reconcile.QualificationReconciler;
import com.fss.biz.reconcile.StockReconciler;
import com.fss.infra.alarm.AlarmService;
import com.fss.infra.config.FssProperties;
import com.fss.infra.lock.DistributedLock;
import com.fss.infra.metrics.SeckillMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 三类对账任务的调度入口。
 *
 * <h3>为什么是三个独立的方法而不是一个</h3>
 * 三类对账的<b>频率不同、成本不同、失败影响不同</b>：
 * <table>
 *   <tr><th>对账</th><th>频率</th><th>成本</th><th>发现不了会怎样</th></tr>
 *   <tr><td>资格</td><td>每分钟</td><td>SCAN Redis，中</td><td>用户永远排队中，库存被占</td></tr>
 *   <tr><td>库存</td><td>每 5 分钟</td><td>SCAN + 多次 DB，高</td><td>超卖或少卖持续扩大</td></tr>
 *   <tr><td>支付</td><td>每 10 分钟</td><td>四条 JOIN 查询，中</td><td>资金与业务不一致</td></tr>
 * </table>
 * 资格对账最频繁，因为它对应的是"用户正在等"——每多一分钟就多一分钟的糟糕体验。
 * 库存对账最贵（要 SCAN 加上每个商品几次 DB 查询），而库存差异不会自己恶化，
 * 5 分钟发现足够。支付对账最不急但最严重，10 分钟是给正常的回调延迟留的窗口。
 *
 * <p>合成一个任务的话，最贵的那个会决定整体频率：要么资格对账被拖慢到 5 分钟，
 * 要么库存对账被提到每分钟——前者让用户多等，后者让 Redis 多扛五倍 SCAN。
 *
 * <h3>每个任务都必须有分布式锁</h3>
 * 与关单任务不同，<b>对账任务的锁保正确而不只省资源</b>：
 * 两个实例同时跑库存对账，可能同时判定"该自动修正"，然后都去覆盖 Redis 库存；
 * 同时跑资格对账则可能对同一个孤儿请求各回补一次（脚本 B 的状态机幂等挡得住，
 * 但轮次计数会被 double 累加，于是本该重发的孤儿提前进入回补）。
 *
 * <h3>任务自身失败必须计入指标</h3>
 * 对账是"上面全部失效时的最后一道防线"，而防线自己失效是无声的——
 * 没有差异被记录，看板上一片正常。{@code fss_job_error_total} 就是为这件事准备的，
 * 告警规则 {@code ReconcileJobFailing} 读它。
 */
@Slf4j
@Component
@Profile("job")
@RequiredArgsConstructor
public class ReconcileJob {

    private final QualificationReconciler qualification;
    private final StockReconciler         stock;
    private final PaymentReconciler       payment;
    private final SeckillMetrics          metrics;
    private final AlarmService            alarm;
    private final FssProperties           props;

    @Scheduled(cron = "${fss.job.reconcile-qualification-cron:0 * * * * ?}")
    @DistributedLock(key = "reconcile-qualification", leaseSeconds = 120)
    public void reconcileQualification() {
        run("reconcile-qualification", qualification::reconcile);
    }

    @Scheduled(cron = "${fss.job.reconcile-stock-cron:0 */5 * * * ?}")
    @DistributedLock(key = "reconcile-stock", leaseSeconds = 240)
    public void reconcileStock() {
        run("reconcile-stock", stock::reconcile);
    }

    @Scheduled(cron = "${fss.job.reconcile-payment-cron:0 */10 * * * ?}")
    @DistributedLock(key = "reconcile-payment", leaseSeconds = 180)
    public void reconcilePayment() {
        var cfg = props.getReconcile();
        run("reconcile-payment", () -> payment.reconcile(
                LocalDateTime.now().minus(cfg.getPaymentDiffAfter()), cfg.getBatchSize()));
    }

    /**
     * 统一的执行包装。
     *
     * <p>异常必须在这里被吞掉：{@code @Scheduled} 方法抛出异常时 Spring 只会打一条
     * 日志，而<b>下一次调度照常进行</b>——所以吞掉不会让任务停摆。真正的理由是
     * 要把它记进指标：不记的话对账失效这件事只存在于日志里，
     * 而"没有差异被记录"和"对账根本没跑成"在看板上长得一模一样。
     */
    private void run(String job, IntTask task) {
        long t0 = System.currentTimeMillis();
        try {
            int diffs = task.run();
            if (diffs > 0) {
                log.warn("stage=JOB_RECONCILE job={} diffs={} cost={}ms",
                        job, diffs, System.currentTimeMillis() - t0);
            } else {
                log.debug("stage=JOB_RECONCILE job={} diffs=0 cost={}ms",
                        job, System.currentTimeMillis() - t0);
            }
        } catch (Exception e) {
            metrics.jobError(job);
            log.error("stage=JOB_RECONCILE job={} result=ERROR cost={}ms",
                    job, System.currentTimeMillis() - t0, e);
            alarm.p2(AlarmService.Event.RECONCILE_FAILED, job,
                    "对账任务执行失败，最后一道防线本轮未生效: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface IntTask {
        int run();
    }
}

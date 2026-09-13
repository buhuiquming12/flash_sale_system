package com.fss.biz.job;

import com.fss.biz.activity.service.WarmupService;
import com.fss.common.trace.TraceContext;
import com.fss.domain.entity.SeckillActivity;
import com.fss.domain.mapper.SeckillActivityMapper;
import com.fss.infra.alarm.AlarmService;
import com.fss.infra.config.FssProperties;
import com.fss.infra.lock.DistributedLock;
import com.fss.infra.metrics.SeckillMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 活动预热任务。
 *
 * <p>在活动开始前 {@code fss.seckill.warmup-ahead}（默认 5 分钟）把库存与元数据
 * 搬进 Redis。<b>提前量不能太小</b>：预热是要读 DB 再写 Redis 的，一场活动几十个
 * SKU，加上任务每分钟才跑一次，留 5 分钟余量才不会出现"到点了还没预热完"。
 *
 * <p>单个活动预热失败不能中断整批——一个配置错误的活动不该让同一批次里
 * 其他活动全部预热不上。失败的活动置 {@code warmup_state = FAILED}，
 * 于是它不会进入 RUNNING（F13），下一轮任务还会自动重试。
 *
 * <p>批内<b>并发</b>执行（{@code fss.job.warmup-concurrency}，默认 4）。
 * 串行预热的风险随活动数线性放大：一场活动几十个 SKU，每个都要一次 DB 往返
 * 加若干次 Redis 写入，串行跑下来很容易吃掉整个 {@code warmup-ahead} 窗口，
 * 表现是"到点了活动还没预热完，用户拿到未预热"。活动之间没有依赖，
 * 唯一的共享点是每个活动自己那把预热锁，并发是安全的。
 */
@Slf4j
@Component
@Profile("job")
@RequiredArgsConstructor
public class WarmupJob {

    private final SeckillActivityMapper activityMapper;
    private final WarmupService         warmupService;
    private final SeckillMetrics        metrics;
    private final AlarmService          alarm;
    private final FssProperties         props;

    /**
     * 预热专用线程池。大小由配置决定，所以在 {@code @PostConstruct} 里建。
     *
     * <p><b>为什么不复用 {@code spring.task.scheduling} 那个池</b>：
     * application.yml 里那个池只有 4 个线程，注释已经写明"关单扫描占满它会让
     * 降级开关整整几分钟不更新，而那恰好是最需要它更新的时候"。预热是一次
     * 可能持续几十秒的批量任务，扔进去正好复现同一个问题。
     *
     * <p><b>拒绝策略是 CallerRunsPolicy，与 LogicalExpiryCache 的重建池刻意相反。</b>
     * 那边丢掉一次缓存重建没有损失——下一个读到逻辑过期的请求会再触发一次；
     * 而这里丢掉一个活动，它的 {@code warmup_state} 就停在未预热，活动永远
     * 进不了进行中，只能等下一轮任务补救。队列满时让提交线程自己跑，
     * 退化成串行——慢，但不会漏掉任何一个活动。
     */
    private ThreadPoolExecutor pool;

    @PostConstruct
    void initPool() {
        int concurrency = Math.max(1, props.getJob().getWarmupConcurrency());
        // 队列容量取并发度的若干倍：容量存在的意义只是触发 CallerRuns 这条
        // 退路，给得太大就等于没有上限，太小则过早退化成串行
        ArrayBlockingQueue<Runnable> queue =
                new ArrayBlockingQueue<>(Math.max(16, concurrency * 8));
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "fss-warmup-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.pool = new ThreadPoolExecutor(concurrency, concurrency,
                60, TimeUnit.SECONDS, queue, factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PreDestroy
    void shutdownPool() {
        if (pool != null) {
            // shutdownNow 而不是 shutdown：应用在关闭，没跑完的活动由
            // 下一轮任务（或重启后）重新捞起来，卡在这里等没有意义。
            // warmupOne 是幂等的（setIfAbsent），被打断也不会写坏数据
            pool.shutdownNow();
        }
    }

    @Scheduled(cron = "${fss.job.warmup-cron:0 * * * * ?}")
    @DistributedLock(key = "warmup", leaseSeconds = 120)
    public void warmup() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.plus(props.getSeckill().getWarmupAhead());
        List<SeckillActivity> list = activityMapper.selectNeedWarmup(deadline, now);
        if (list.isEmpty()) {
            return;
        }

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        List<Callable<Void>> tasks = new ArrayList<>(list.size());
        for (SeckillActivity a : list) {
            long activityId = a.getId();
            // 显式声明成 Callable 而不是裸 lambda：TraceContext 同时有
            // wrap(Runnable) 与 wrap(Callable)，裸 lambda 两者都匹配、编译不过。
            // LogicalExpiryCache 里记着同一个坑，这里照它的写法办
            Callable<Void> task = () -> {
                if (warmUp(activityId)) {
                    ok.incrementAndGet();
                } else {
                    failed.incrementAndGet();
                }
                return null;
            };
            tasks.add(TraceContext.wrap(task));
        }

        try {
            // <b>必须阻塞到全部结束</b>，不能用 submit 后返回：这个方法由
            // @Scheduled 驱动，而"预热完成"是活动进入 RUNNING 的前置条件
            // （warmup_state = 2）——不等完就返回，会出现活动已经能被抢、
            // 而 Redis 里还没有库存的窗口。集成测试也依赖这一点：
            // 调用方在 warmup() 返回后<b>立刻</b>断言状态与库存
            pool.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("stage=JOB_WARMUP total={} result=INTERRUPTED 剩余活动留待下一轮",
                    list.size(), e);
            return;
        }

        log.info("stage=JOB_WARMUP total={} ok={} failed={} deadline={}",
                list.size(), ok.get(), failed.get(), deadline);
    }

    /**
     * 预热一个活动，<b>失败只影响它自己</b>。
     *
     * @return 是否成功。失败不抛出——抛出会让 {@code invokeAll} 的调用方
     *         拿不到其余任务的结果，失败隔离就此失效
     */
    private boolean warmUp(long activityId) {
        try {
            warmupService.warmupOne(activityId);
            return true;
        } catch (Exception e) {
            metrics.jobError("warmup");
            log.error("stage=JOB_WARMUP activityId={} result=ERROR 活动将不会进入进行中",
                    activityId, e);
            alarm.p2(AlarmService.Event.WARMUP_FAILED, String.valueOf(activityId),
                    "活动预热失败，不会进入进行中: " + e.getMessage());
            try {
                warmupService.markFailed(activityId);
            } catch (Exception ignored) {
                log.error("stage=JOB_WARMUP activityId={} 预热失败状态也没写进去", activityId);
            }
            return false;
        }
    }
}

package com.fss.biz.job;

import com.fss.biz.activity.service.WarmupService;
import com.fss.domain.entity.SeckillActivity;
import com.fss.domain.mapper.SeckillActivityMapper;
import com.fss.infra.config.FssProperties;
import com.fss.infra.lock.DistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

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
 */
@Slf4j
@Component
@Profile("job")
@RequiredArgsConstructor
public class WarmupJob {

    private final SeckillActivityMapper activityMapper;
    private final WarmupService         warmupService;
    private final FssProperties         props;

    @Scheduled(cron = "${fss.job.warmup-cron:0 * * * * ?}")
    @DistributedLock(key = "warmup", leaseSeconds = 120)
    public void warmup() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.plus(props.getSeckill().getWarmupAhead());
        List<SeckillActivity> list = activityMapper.selectNeedWarmup(deadline, now);
        if (list.isEmpty()) {
            return;
        }

        int ok = 0;
        int failed = 0;
        for (SeckillActivity a : list) {
            try {
                warmupService.warmupOne(a.getId());
                ok++;
            } catch (Exception e) {
                failed++;
                log.error("stage=JOB_WARMUP activityId={} result=ERROR 活动将不会进入进行中",
                        a.getId(), e);
                try {
                    warmupService.markFailed(a.getId());
                } catch (Exception ignored) {
                    log.error("stage=JOB_WARMUP activityId={} 预热失败状态也没写进去", a.getId());
                }
            }
        }
        log.info("stage=JOB_WARMUP total={} ok={} failed={} deadline={}",
                list.size(), ok, failed, deadline);
    }
}

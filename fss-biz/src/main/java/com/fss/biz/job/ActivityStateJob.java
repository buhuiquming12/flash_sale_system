package com.fss.biz.job;

import com.fss.common.enums.ActivityStatus;
import com.fss.domain.entity.SeckillGoods;
import com.fss.domain.mapper.SeckillActivityMapper;
import com.fss.domain.mapper.SeckillGoodsMapper;
import com.fss.infra.lock.DistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动状态推进。
 *
 * <p>活动状态不靠人工点击，由定时任务按时间推进——人工点击意味着凌晨的活动需要有人
 * 值守，而且点晚了就是直接的业务损失。
 *
 * <p>加分布式锁（F12：3 个 job 实例，每个任务只执行一次）。这里的锁同样只省资源：
 * {@code startReadyActivities} 与 {@code endActivity} 都是条件更新，
 * 多实例并发执行不会把一个活动推进两次。
 */
@Slf4j
@Component
@Profile("job")
@RequiredArgsConstructor
public class ActivityStateJob {

    private final SeckillActivityMapper activityMapper;
    private final SeckillGoodsMapper    goodsMapper;

    @Scheduled(cron = "${fss.job.activity-state-cron:0 * * * * ?}")
    @DistributedLock(key = "activity-state", leaseSeconds = 60)
    @Transactional(rollbackFor = Exception.class)
    public void advanceState() {
        LocalDateTime now = LocalDateTime.now();

        // READY → RUNNING。条件里带 warmup_state = 2，预热未完成的活动不会进入 RUNNING
        int started = activityMapper.startReadyActivities(now);
        if (started > 0) {
            log.info("stage=JOB_ACTIVITY_STATE started={}", started);
        }

        // RUNNING → ENDED
        List<Long> ended = activityMapper.selectRunningEnded(now);
        for (Long id : ended) {
            if (activityMapper.endActivity(id) > 0) {
                // 停售全部商品，让后续请求快速失败而不是走完整校验链
                goodsMapper.updateStatusByActivity(id, SeckillGoods.STATUS_OFF_SALE);
                log.info("stage=JOB_ACTIVITY_STATE activityId={} to={}",
                        id, ActivityStatus.ENDED.code());
            }
        }
    }
}

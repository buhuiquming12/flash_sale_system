package com.fss.biz.job;

import com.fss.domain.mapper.MqMessageMapper;
import com.fss.infra.config.FssProperties;
import com.fss.infra.lock.DistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 分批清理超过保留期的已消费/发送失败消息。 */
@Slf4j @Component @Profile("job") @RequiredArgsConstructor
public class MqMessageCleanupJob {
    private static final int BATCH = 1000;
    private final MqMessageMapper mapper;
    private final FssProperties props;

    @Scheduled(cron = "${fss.job.mq-cleanup-cron:0 20 3 * * ?}")
    @DistributedLock(key = "mq-message-cleanup", leaseSeconds = 300)
    public void cleanup() {
        LocalDateTime before = LocalDateTime.now().minus(props.getMq().getTerminalRetention());
        int total = 0;
        int rows;
        do {
            rows = mapper.deleteTerminalBefore(before, BATCH);
            total += rows;
        } while (rows == BATCH && total < 100_000);
        if (total > 0) log.info("stage=MQ_CLEANUP deleted={} before={}", total, before);
    }
}

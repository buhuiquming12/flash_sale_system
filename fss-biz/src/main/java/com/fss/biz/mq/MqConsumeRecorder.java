package com.fss.biz.mq;

import com.fss.common.enums.MqStatus;
import com.fss.domain.entity.MqMessage;
import com.fss.domain.mapper.MqMessageMapper;
import com.fss.infra.metrics.SeckillMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/** 原子推进消费状态，并记录“登记→消费”的端到端时延。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqConsumeRecorder {

    private final MqMessageMapper mapper;
    private final SeckillMetrics metrics;

    public void consumed(String bizKey, String topic) {
        try {
            MqMessage rec = mapper.selectByBizKeyAndTopic(bizKey, topic);
            int changed = mapper.markConsumedByBizKey(bizKey, topic);
            if (changed > 0 && rec != null && rec.getCreateTime() != null
                    && rec.getStatus() == MqStatus.SENT.code()) {
                long nanos = Duration.between(rec.getCreateTime(), LocalDateTime.now()).toNanos();
                metrics.mqDeliveryLatency(topic, Math.max(0L, nanos));
            }
        } catch (Exception e) {
            // 归档状态和指标均不参与业务正确性，不能为此重投已经处理成功的消息。
            log.debug("标记消息已消费失败 keys={} topic={}", bizKey, topic, e);
        }
    }
}

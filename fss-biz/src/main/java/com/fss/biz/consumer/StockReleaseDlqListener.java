package com.fss.biz.consumer;

import com.fss.biz.reconcile.ReconcileRecorder;
import com.fss.biz.seckill.core.RollbackOutcome;
import com.fss.biz.seckill.core.SeckillExecutor;
import com.fss.common.enums.ReconcileTaskStatus;
import com.fss.common.enums.ReconcileTaskType;
import com.fss.common.util.JsonUtil;
import com.fss.domain.message.StockReleaseMessage;
import com.fss.infra.alarm.AlarmService;
import com.fss.infra.metrics.SeckillMetrics;
import com.fss.infra.mq.MqTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 取消回补死信：先落库存对账与 P1，再同步 release 兜底一次。 */
@Slf4j @Component @Profile("consumer") @RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.DLQ_STOCK_RELEASE,
        consumerGroup = MqTopics.GID_DLQ_STOCK_RELEASE_HANDLER,
        consumeMode = ConsumeMode.CONCURRENTLY, consumeThreadNumber = 2)
public class StockReleaseDlqListener implements RocketMQListener<MessageExt> {
    private final ReconcileRecorder recorder;
    private final SeckillMetrics metrics;
    private final AlarmService alarm;
    private final SeckillExecutor executor;

    @Override public void onMessage(MessageExt ext) {
        try { handle(ext); }
        catch (Exception e) { log.error("stage=DLQ_STOCK_RELEASE keys={} result=HANDLER_FAILED 已ACK转人工", ext.getKeys(), e); }
    }

    private void handle(MessageExt ext) {
        String body = new String(ext.getBody(), StandardCharsets.UTF_8);
        StockReleaseMessage msg = parse(body, ext.getKeys());
        String bizNo = msg == null ? ext.getKeys() : msg.getOrderNo();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("topic", ext.getTopic()); detail.put("body", body);
        detail.put("reconsumeTimes", ext.getReconsumeTimes());
        recorder.record(ReconcileTaskType.STOCK, bizNo,
                msg == null ? null : msg.getActivityId(), msg == null ? null : msg.getSkuId(),
                detail, ReconcileTaskStatus.NEED_MANUAL, "取消库存回补消息进入死信");
        metrics.dlq(MqTopics.STOCK_RELEASE);
        alarm.p1(AlarmService.Event.MQ_DLQ, bizNo, "取消库存回补进入死信，库存可能泄漏");
        if (msg == null) return;
        try {
            RollbackOutcome outcome = executor.release(msg.getActivityId(), msg.getSkuId(),
                    msg.getOrderNo(), msg.getQuantity());
            log.error("stage=DLQ_STOCK_RELEASE orderNo={} result=SYNC_FALLBACK outcome={}", bizNo, outcome);
        } catch (Exception e) {
            log.error("stage=DLQ_STOCK_RELEASE orderNo={} result=SYNC_FALLBACK_FAILED 已ACK转人工", bizNo, e);
        }
    }

    private StockReleaseMessage parse(String body, String key) {
        try { return JsonUtil.parse(body, StockReleaseMessage.class); }
        catch (Exception e) { log.error("stage=DLQ_STOCK_RELEASE keys={} result=UNPARSEABLE", key, e); return null; }
    }
}

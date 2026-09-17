package com.fss.biz.consumer;

import com.fss.biz.reconcile.ReconcileRecorder;
import com.fss.biz.seckill.core.RollbackOutcome;
import com.fss.biz.seckill.core.SeckillCompensateService;
import com.fss.common.enums.ReconcileTaskStatus;
import com.fss.common.enums.ReconcileTaskType;
import com.fss.common.error.ErrorCode;
import com.fss.common.util.JsonUtil;
import com.fss.domain.message.OrderCreateMessage;
import com.fss.domain.message.StockRollbackMessage;
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

/** 补偿回补死信：先落库存对账与 P1，再同步 rollback 兜底一次。 */
@Slf4j @Component @Profile("consumer") @RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.DLQ_STOCK_ROLLBACK,
        consumerGroup = MqTopics.GID_DLQ_STOCK_ROLLBACK_HANDLER,
        consumeMode = ConsumeMode.CONCURRENTLY, consumeThreadNumber = 2)
public class StockRollbackDlqListener implements RocketMQListener<MessageExt> {
    private final ReconcileRecorder recorder;
    private final SeckillMetrics metrics;
    private final AlarmService alarm;
    private final SeckillCompensateService compensateService;

    @Override public void onMessage(MessageExt ext) {
        try { handle(ext); }
        catch (Exception e) { log.error("stage=DLQ_STOCK_ROLLBACK keys={} result=HANDLER_FAILED 已ACK转人工", ext.getKeys(), e); }
    }

    private void handle(MessageExt ext) {
        String body = new String(ext.getBody(), StandardCharsets.UTF_8);
        StockRollbackMessage msg = parse(body, ext.getKeys());
        String bizNo = msg == null ? ext.getKeys() : msg.getRequestNo();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("topic", ext.getTopic()); detail.put("body", body);
        detail.put("reconsumeTimes", ext.getReconsumeTimes());
        recorder.record(ReconcileTaskType.STOCK, bizNo,
                msg == null ? null : msg.getActivityId(), msg == null ? null : msg.getSkuId(),
                detail, ReconcileTaskStatus.NEED_MANUAL, "补偿库存回补消息进入死信");
        metrics.dlq(MqTopics.STOCK_ROLLBACK);
        alarm.p1(AlarmService.Event.MQ_DLQ, bizNo, "补偿库存回补进入死信，库存可能泄漏");
        if (msg == null) return;
        try {
            ErrorCode ec = Boolean.TRUE.equals(msg.getKeepBought())
                    ? ErrorCode.ALREADY_BOUGHT : ErrorCode.SYSTEM_BUSY;
            RollbackOutcome outcome = compensateService.rollback(toCreate(msg), ec, msg.getReason());
            log.error("stage=DLQ_STOCK_ROLLBACK requestNo={} result=SYNC_FALLBACK outcome={}", bizNo, outcome);
        } catch (Exception e) {
            log.error("stage=DLQ_STOCK_ROLLBACK requestNo={} result=SYNC_FALLBACK_FAILED 已ACK转人工", bizNo, e);
        }
    }

    private StockRollbackMessage parse(String body, String key) {
        try { return JsonUtil.parse(body, StockRollbackMessage.class); }
        catch (Exception e) { log.error("stage=DLQ_STOCK_ROLLBACK keys={} result=UNPARSEABLE", key, e); return null; }
    }

    private OrderCreateMessage toCreate(StockRollbackMessage m) {
        return OrderCreateMessage.builder().requestNo(m.getRequestNo()).userId(m.getUserId())
                .activityId(m.getActivityId()).skuId(m.getSkuId()).quantity(m.getQuantity())
                .traceId(m.getTraceId()).version(OrderCreateMessage.CURRENT_VERSION).build();
    }
}

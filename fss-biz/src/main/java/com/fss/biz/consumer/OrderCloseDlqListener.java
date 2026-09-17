package com.fss.biz.consumer;

import com.fss.biz.order.service.OrderService;
import com.fss.biz.reconcile.ReconcileRecorder;
import com.fss.common.enums.ReconcileTaskStatus;
import com.fss.common.enums.ReconcileTaskType;
import com.fss.common.util.JsonUtil;
import com.fss.domain.entity.Order;
import com.fss.domain.mapper.OrderMapper;
import com.fss.domain.message.OrderCloseMessage;
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

/** 关单死信：留痕后只补关仍处于待支付状态的订单，任何异常都 ACK。 */
@Slf4j
@Component
@Profile("consumer")
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.DLQ_ORDER_CLOSE,
        consumerGroup = MqTopics.GID_DLQ_ORDER_CLOSE_HANDLER,
        consumeMode = ConsumeMode.CONCURRENTLY, consumeThreadNumber = 2)
public class OrderCloseDlqListener implements RocketMQListener<MessageExt> {

    private final ReconcileRecorder recorder;
    private final SeckillMetrics metrics;
    private final AlarmService alarm;
    private final OrderMapper orderMapper;
    private final OrderService orderService;

    @Override
    public void onMessage(MessageExt ext) {
        try {
            handle(ext);
        } catch (Exception e) {
            log.error("stage=DLQ_ORDER_CLOSE keys={} result=HANDLER_FAILED 已ACK转人工", ext.getKeys(), e);
        }
    }

    private void handle(MessageExt ext) {
        String body = new String(ext.getBody(), StandardCharsets.UTF_8);
        OrderCloseMessage msg = parse(body, ext.getKeys());
        String orderNo = msg == null ? ext.getKeys() : msg.getOrderNo();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("topic", ext.getTopic());
        detail.put("body", body);
        detail.put("reconsumeTimes", ext.getReconsumeTimes());
        recorder.record(ReconcileTaskType.ORDER, orderNo, null, null, detail,
                ReconcileTaskStatus.NEED_MANUAL, "关单消息进入死信");
        metrics.dlq(MqTopics.ORDER_CLOSE);
        alarm.p2(AlarmService.Event.MQ_DLQ, orderNo, "关单消息进入死信队列");

        if (msg == null || msg.getOrderNo() == null) return;
        try {
            Order order = orderMapper.selectByOrderNo(msg.getOrderNo());
            if (order != null && order.getStatus() == 0) {
                boolean closed = orderService.closeOrder(msg.getOrderNo(), "关单死信自动兜底");
                log.warn("stage=DLQ_ORDER_CLOSE orderNo={} result=AUTO_CLOSE closed={}", orderNo, closed);
            } else {
                log.info("stage=DLQ_ORDER_CLOSE orderNo={} result=SKIP status={}", orderNo,
                        order == null ? null : order.getStatus());
            }
        } catch (Exception e) {
            log.error("stage=DLQ_ORDER_CLOSE orderNo={} result=AUTO_CLOSE_FAILED 已ACK转人工", orderNo, e);
        }
    }

    private OrderCloseMessage parse(String body, String key) {
        try { return JsonUtil.parse(body, OrderCloseMessage.class); }
        catch (Exception e) {
            log.error("stage=DLQ_ORDER_CLOSE keys={} result=UNPARSEABLE", key, e);
            return null;
        }
    }
}

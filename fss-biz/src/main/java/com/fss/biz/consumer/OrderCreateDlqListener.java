package com.fss.biz.consumer;

import com.fss.biz.seckill.core.RollbackOutcome;
import com.fss.biz.seckill.core.SeckillCompensateService;
import com.fss.common.enums.ReconcileTaskStatus;
import com.fss.common.enums.ReconcileTaskType;
import com.fss.common.error.ErrorCode;
import com.fss.common.trace.TraceContext;
import com.fss.common.util.JsonUtil;
import com.fss.domain.entity.ReconcileTask;
import com.fss.domain.mapper.ReconcileTaskMapper;
import com.fss.domain.message.OrderCreateMessage;
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

/**
 * 订单创建死信消费者。
 *
 * <p><b>死信队列必须有消费者。</b> 没有消费者的死信队列等于数据静默丢失——
 * 消息堆在那里，没人知道有 300 个用户扣了库存却没有订单。这是异步化最容易留下的
 * 窟窿：主链路的代码全都测过，唯独"重试都失败之后呢"这条路没人走过。
 *
 * <p>做两件事，顺序不能反：
 * <ol>
 *   <li><b>先落对账任务</b>，人工可见。自动回补万一也失败了，至少还有一条记录
 *       说明"这个 requestNo 需要人看一眼"。</li>
 *   <li><b>再自动回补</b>，不等人工。等人工意味着这一份库存要占到有人上班，
 *       而活动可能只有 10 分钟。</li>
 * </ol>
 *
 * <p>这个消费者<b>绝不抛异常</b>：死信的死信是没有归宿的，抛出去只会让同一条消息
 * 在死信 Topic 里反复投递、反复插对账任务。处理不了就记 P1 日志。
 */
@Slf4j
@Component
@Profile("consumer")
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.DLQ_ORDER_CREATE,
        consumerGroup = MqTopics.GID_DLQ_HANDLER,
        consumeMode = ConsumeMode.CONCURRENTLY,
        consumeThreadNumber = 2)
public class OrderCreateDlqListener implements RocketMQListener<MessageExt> {

    private final SeckillCompensateService compensateService;
    private final ReconcileTaskMapper      reconcileMapper;
    private final SeckillMetrics           metrics;
    private final AlarmService             alarm;

    @Override
    public void onMessage(MessageExt ext) {
        String body = new String(ext.getBody(), StandardCharsets.UTF_8);
        log.error("stage=DLQ topic={} keys={} reconsume={} body={}",
                ext.getTopic(), ext.getKeys(), ext.getReconsumeTimes(), body);
        metrics.dlq(MqTopics.ORDER_CREATE);

        OrderCreateMessage msg = null;
        try {
            msg = JsonUtil.parse(body, OrderCreateMessage.class);
        } catch (Exception e) {
            log.error("stage=DLQ result=UNPARSEABLE keys={} 只能转人工", ext.getKeys(), e);
        }

        recordTask(ext, body, msg);
        alarm.p2(AlarmService.Event.MQ_DLQ, ext.getKeys(), "订单创建消息进入死信队列");

        if (msg == null) {
            return;
        }
        TraceContext.set(msg.getTraceId());
        try {
            // 进了死信说明消费端已经试过 5 次都不成，原因未知（可能是 DB 长期不可用、
            // 也可能是一条永远处理不了的脏数据）。无论哪种，库存不能继续被占着。
            // keepBought = false：用户没有责任，资格还给他，让他能重抢
            RollbackOutcome outcome = compensateService.rollback(msg, ErrorCode.SYSTEM_BUSY,
                    "消息进入死信队列，已自动回补");
            if (outcome.isFailed()) {
                // 死信里的回补也失败了：没有更后面的兜底了，只能靠库存对账，
                // 所以按 P1 报出来，别混在正常的"已自动回补"里
                log.error("stage=DLQ requestNo={} result=ROLLBACK_FAILED 库存泄漏", msg.getRequestNo());
                alarm.p1(AlarmService.Event.MQ_DLQ, msg.getRequestNo(),
                        "死信消息回补失败，库存泄漏");
            } else {
                log.error("stage=DLQ requestNo={} autoRollback={} 已告警",
                        msg.getRequestNo(), outcome);
            }
        } catch (Exception e) {
            // 死信的死信没有归宿。绝不能抛
            log.error("stage=DLQ requestNo={} result=ROLLBACK_FAILED 转人工",
                    msg.getRequestNo(), e);
            alarm.p1(AlarmService.Event.MQ_DLQ, msg.getRequestNo(),
                    "死信消息回补异常，库存未回补需人工");
        } finally {
            TraceContext.clear();
        }
    }

    private void recordTask(MessageExt ext, String body, OrderCreateMessage msg) {
        try {
            reconcileMapper.insert(ReconcileTask.builder()
                    .taskType(ReconcileTaskType.QUALIFICATION.code())
                    .bizNo(msg != null ? msg.getRequestNo() : ext.getKeys())
                    .activityId(msg != null ? msg.getActivityId() : null)
                    .skuId(msg != null ? msg.getSkuId() : null)
                    .detail(body)
                    .status(ReconcileTaskStatus.NEED_MANUAL.code())
                    .build());
            metrics.reconcileDiff("qualification", "need_manual");
        } catch (Exception e) {
            log.error("stage=DLQ keys={} result=RECORD_FAILED 对账任务都没落上，只剩这条日志",
                    ext.getKeys(), e);
        }
    }
}

package com.fss.biz.job;

import com.fss.biz.mq.ReliableMqProducer;
import com.fss.biz.seckill.core.SeckillCompensateService;
import com.fss.common.error.ErrorCode;
import com.fss.common.trace.TraceContext;
import com.fss.common.util.JsonUtil;
import com.fss.domain.entity.MqMessage;
import com.fss.domain.mapper.MqMessageMapper;
import com.fss.domain.message.OrderCreateMessage;
import com.fss.infra.alarm.AlarmService;
import com.fss.infra.config.FssProperties;
import com.fss.infra.lock.DistributedLock;
import com.fss.infra.metrics.SeckillMetrics;
import com.fss.infra.mq.MqTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息重发任务 —— 本地消息表这套机制真正兑现承诺的地方。
 *
 * <p>没有它，本地消息表只是一张没人读的日志表：投递意图落了盘，却没有任何东西
 * 会去把没发出去的消息补发。故障用例 F4（断开 MQ 后压测）能不能过，全看这个类。
 *
 * <h3>两段职责</h3>
 * <ol>
 *   <li><b>退避重发</b>：30s → 60s → 120s → 240s → 480s。指数退避而不是固定间隔——
 *       MQ 挂了的时候固定 30 秒重试会让几万条积压消息每 30 秒对 broker 发起一次
 *       集体冲击，恢复过程被自己拖慢。</li>
 *   <li><b>放弃即回补</b>（{@code onSendGiveUp}）：重试耗尽必须回补库存，
 *       否则库存被永久占用。这是原设计文档缺失的一环，也是最容易漏的一环——
 *       "发不出去"和"发出去但处理失败"的兜底路径完全不同，后者有死信队列，
 *       前者只有这里。</li>
 * </ol>
 *
 * <p>两段拆成两次查询而不是一次捞出来再分流：{@code selectPendingForRetry} 带了
 * {@code send_count < max} 条件，超次数的记录不会挤占每轮 200 条的配额。
 * 合成一条查询的话，几万条已放弃的记录会把配额占满，
 * 真正需要重发的新消息一条也捞不到——这种队头阻塞在量小时完全看不出来。
 */
@Slf4j
@Component
@Profile("job")
@RequiredArgsConstructor
public class MqResendJob {

    /** 单轮处理上限。够大以免积压清不完，够小以免一轮跑太久拖过锁的租期 */
    private static final int BATCH = 200;

    private final MqMessageMapper          mapper;
    private final ReliableMqProducer       producer;
    private final SeckillCompensateService compensateService;
    private final SeckillMetrics           metrics;
    private final AlarmService             alarm;
    private final FssProperties            props;

    @Scheduled(fixedDelayString = "${fss.job.mq-resend-delay-ms:30000}")
    @DistributedLock(key = "mq-resend", leaseSeconds = 120)
    public void resend() {
        int max = props.getMq().getMaxResend();

        int sent = 0;
        int failed = 0;
        for (MqMessage rec : mapper.selectPendingForRetry(LocalDateTime.now(), max, BATCH)) {
            TraceContext.set(null);
            try {
                producer.doSend(rec);
                metrics.mqResend(rec.getTopic());
                sent++;
            } catch (Exception e) {
                failed++;
                // 30 << sendCount：30 → 60 → 120 → 240 → 480
                long backoff = 30L << Math.min(rec.getSendCount(), 4);
                mapper.markRetry(rec.getMsgId(), backoff,
                        ReliableMqProducer.truncate(e.getMessage()));
                log.warn("stage=MQ_RESEND msgId={} bizKey={} sendCount={} backoff={}s",
                        rec.getMsgId(), rec.getBizKey(), rec.getSendCount(), backoff, e);
            } finally {
                TraceContext.clear();
            }
        }

        int gaveUp = 0;
        for (MqMessage rec : mapper.selectExhausted(max, BATCH)) {
            onSendGiveUp(rec);
            gaveUp++;
        }

        if (sent > 0 || failed > 0 || gaveUp > 0) {
            log.info("stage=JOB_MQ_RESEND sent={} failed={} gaveUp={}", sent, failed, gaveUp);
        }
    }

    /**
     * 放弃投递。
     *
     * <p>先 {@code markFailed} 再回补：顺序反了的话，回补成功而 markFailed 失败时，
     * 下一轮又会把同一条记录捞出来再回补一次。虽然脚本 B 的状态机幂等挡得住，
     * 但那意味着每 30 秒重复一次无用功，还会不断刷 P1 日志。
     * 先置终态则至多多回补一次（第一次 markFailed 成功、回补失败的情况），
     * 而那一次本来就该重试。
     */
    private void onSendGiveUp(MqMessage rec) {
        mapper.markFailed(rec.getMsgId(), "超过最大重发次数 " + props.getMq().getMaxResend());
        metrics.mqGiveUp(rec.getTopic());

        if (!MqTopics.ORDER_CREATE.equals(rec.getTopic())) {
            // 关单、回补类消息发不出去不涉及库存泄漏：关单有定时扫描兜底，
            // 回补有对账兜底。记 P2 日志等人看
            log.error("stage=MQ_GIVE_UP msgId={} topic={} bizKey={} 需人工处理",
                    rec.getMsgId(), rec.getTopic(), rec.getBizKey());
            alarm.p2(AlarmService.Event.MQ_GIVE_UP, rec.getBizKey(),
                    rec.getTopic() + " 投递放弃，有兜底路径");
            return;
        }

        // 订单创建消息彻底发不出去 → 没有任何人会为这个用户建订单 → 必须回补
        try {
            OrderCreateMessage msg = JsonUtil.parse(rec.getBody(), OrderCreateMessage.class);
            TraceContext.set(msg.getTraceId());
            boolean done = compensateService.rollback(msg, ErrorCode.SYSTEM_BUSY,
                    "订单消息投递失败，已退回");
            log.error("stage=MQ_GIVE_UP msgId={} requestNo={} autoRollback={} 已告警",
                    rec.getMsgId(), msg.getRequestNo(), done);
            alarm.p2(AlarmService.Event.MQ_GIVE_UP, msg.getRequestNo(),
                    "订单创建消息投递放弃，已自动回补=" + done);
        } catch (Exception e) {
            log.error("stage=MQ_GIVE_UP msgId={} bizKey={} result=ROLLBACK_FAILED 转人工",
                    rec.getMsgId(), rec.getBizKey(), e);
            alarm.p1(AlarmService.Event.MQ_GIVE_UP, rec.getBizKey(),
                    "订单创建消息投递放弃且回补失败，库存泄漏");
        } finally {
            TraceContext.clear();
        }
    }
}

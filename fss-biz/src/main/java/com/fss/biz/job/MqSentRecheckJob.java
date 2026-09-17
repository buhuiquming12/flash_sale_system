package com.fss.biz.job;

import com.fss.biz.reconcile.ReconcileRecorder;
import com.fss.common.enums.ReconcileTaskStatus;
import com.fss.common.enums.ReconcileTaskType;
import com.fss.domain.entity.MqMessage;
import com.fss.domain.mapper.MqMessageMapper;
import com.fss.infra.alarm.AlarmService;
import com.fss.infra.config.FssProperties;
import com.fss.infra.lock.DistributedLock;
import com.fss.infra.mq.MqTopics;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.QueryResult;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.admin.OffsetWrapper;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 复核长期停在 SENT 的记录，区分“已消费但回写失败”和 broker 中确实不存在。 */
@Slf4j @Component @Profile("job") @RequiredArgsConstructor
@ConditionalOnProperty(name = "fss.mq.backlog-monitor-enabled", havingValue = "true")
public class MqSentRecheckJob {
    private static final int BATCH = 200;
    private final MqMessageMapper mapper;
    private final ReconcileRecorder recorder;
    private final AlarmService alarm;
    private final FssProperties props;
    @Value("${rocketmq.name-server}") private String nameServer;
    private volatile DefaultMQAdminExt admin;

    @Scheduled(fixedDelayString = "${fss.job.mq-sent-recheck-delay-ms:60000}")
    @DistributedLock(key = "mq-sent-recheck", leaseSeconds = 120)
    public void recheck() {
        LocalDateTime before = LocalDateTime.now().minus(props.getMq().getSentRecheckAfter());
        for (MqMessage rec : mapper.selectSentBefore(before, BATCH)) {
            try {
                Verdict verdict = inspect(rec);
                if (verdict == Verdict.CONSUMED) {
                    mapper.markConsumed(rec.getMsgId());
                } else if (verdict == Verdict.LOST) {
                    recordLost(rec);
                }
            } catch (Exception e) {
                // broker/admin 不可用不是“消息丢失”，本轮无法确认就保持 SENT。
                log.warn("stage=MQ_SENT_RECHECK msgId={} result=CHECK_FAILED", rec.getMsgId(), e);
            }
        }
    }

    private Verdict inspect(MqMessage rec) throws Exception {
        long begin = rec.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        QueryResult qr = client().queryMessage(rec.getTopic(), rec.getBizKey(), 32,
                Math.max(0, begin - 60_000), System.currentTimeMillis());
        List<MessageExt> found = qr == null ? List.of() : qr.getMessageList();
        if (found == null || found.isEmpty()) return Verdict.LOST;

        String group = groupOf(rec.getTopic());
        Map<MessageQueue, OffsetWrapper> offsets = client()
                .examineConsumeStats(group, rec.getTopic()).getOffsetTable();
        boolean pending = false;
        for (MessageExt ext : found) {
            MessageQueue queue = new MessageQueue(rec.getTopic(), ext.getBrokerName(), ext.getQueueId());
            OffsetWrapper wrapper = offsets.get(queue);
            if (wrapper != null && wrapper.getConsumerOffset() > ext.getQueueOffset()) return Verdict.CONSUMED;
            pending = true;
        }
        return pending ? Verdict.PENDING : Verdict.LOST;
    }

    private void recordLost(MqMessage rec) {
        ReconcileTaskType type = switch (rec.getTopic()) {
            case MqTopics.ORDER_CLOSE -> ReconcileTaskType.ORDER;
            case MqTopics.STOCK_RELEASE, MqTopics.STOCK_ROLLBACK -> ReconcileTaskType.STOCK;
            default -> ReconcileTaskType.QUALIFICATION;
        };
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("msgId", rec.getMsgId()); detail.put("topic", rec.getTopic());
        detail.put("bizKey", rec.getBizKey()); detail.put("sentAt", rec.getUpdateTime());
        boolean inserted = recorder.record(type, rec.getBizKey(), null, null, detail,
                ReconcileTaskStatus.NEED_MANUAL, "本地记录已发送，但 broker 查无消息");
        if (inserted) alarm.p1(AlarmService.Event.MQ_MESSAGE_LOST, rec.getBizKey(),
                rec.getTopic() + " broker 查无消息");
    }

    private String groupOf(String topic) {
        var g = props.getMq().getGroup();
        return switch (topic) {
            case MqTopics.ORDER_CLOSE -> g.getOrderClose();
            case MqTopics.STOCK_RELEASE -> g.getStockRelease();
            case MqTopics.STOCK_ROLLBACK -> g.getStockRollback();
            default -> g.getOrderCreate();
        };
    }

    private DefaultMQAdminExt client() throws Exception {
        if (admin == null) synchronized (this) {
            if (admin == null) {
                DefaultMQAdminExt a = new DefaultMQAdminExt(new AclClientRPCHook(
                        new SessionCredentials(props.getMq().getAccessKey(),
                                props.getMq().getSecretKey())));
                a.setNamesrvAddr(nameServer); a.setInstanceName("fss-sent-recheck-admin"); a.start();
                admin = a;
            }
        }
        return admin;
    }

    @PreDestroy public void shutdown() { if (admin != null) admin.shutdown(); }
    private enum Verdict { CONSUMED, PENDING, LOST }
}

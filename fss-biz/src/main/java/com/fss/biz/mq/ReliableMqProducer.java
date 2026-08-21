package com.fss.biz.mq;

import com.fss.common.enums.MqStatus;
import com.fss.common.trace.TraceContext;
import com.fss.common.util.IdGenerator;
import com.fss.common.util.JsonUtil;
import com.fss.domain.entity.MqMessage;
import com.fss.domain.mapper.MqMessageMapper;
import com.fss.infra.mq.MqSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

/**
 * 可靠消息投递（决策 5：本地消息表）。
 *
 * <p><b>解决的是一个很具体的窗口</b>：Redis 已经扣了库存，进程在调 send 之前崩溃。
 * 此时用户的资格已经分配出去，却没有任何人知道要为他建订单——内存里的重试意图
 * 随进程消失，1000 库存的活动最后只卖出 997 件，而账面显示已售罄。
 * 先把"要投递什么"写进 MySQL，崩溃重启后重发任务能从表里读出来接着发。
 *
 * <p>代价是一次非热点单行 INSERT。真正的热点是 {@code t_seckill_goods} 的库存行，
 * 那一行已经由 Redis 挡在前面；{@code t_mq_message} 是纯追加写，
 * 不同请求写不同行，没有行锁竞争。
 *
 * <h3>两个入口的区别</h3>
 * <table>
 *   <tr><th></th><th>{@link #sendReliable}</th><th>{@link #registerAfterCommit}</th></tr>
 *   <tr><td>调用位置</td><td>事务外（秒杀主链路）</td><td>事务内（订单落库、关单）</td></tr>
 *   <tr><td>消息行何时可见</td><td>立刻，自己一个事务</td><td>随业务事务一起提交</td></tr>
 *   <tr><td>保证</td><td>投递意图不丢</td><td>投递意图与业务数据<b>原子</b></td></tr>
 * </table>
 *
 * <p>{@code registerAfterCommit} 才是本地消息表的精髓：消息行与业务数据同事务，
 * 所以"订单已创建"和"关单消息已登记"不可能只发生一半。而 send 动作挪到提交之后，
 * 避免"消息已投递但订单事务回滚"——消费端会去处理一张根本不存在的订单。
 *
 * <p><b>发送失败一律不向上抛。</b> 落库已经成功，投递意图不会丢，重发任务会兜住。
 * 抛出去只会让主链路失败、白触发一次库存回补，而用户本来是能拿到订单的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReliableMqProducer {

    /** 首次重试的延迟。正常路径 1 秒内就发出去并置成已发送，留点余量避免和首发撞车 */
    private static final long FIRST_BACKOFF_SECONDS = 30;

    private final MqSender        sender;
    private final MqMessageMapper mapper;

    /** 事务外投递：先落库，再发送。 */
    public void sendReliable(String topic, String bizKey, Object body,
                             LocalDateTime deliverTime) {
        MqMessage rec = build(topic, bizKey, body, deliverTime);
        mapper.insert(rec);
        trySend(rec);
    }

    /**
     * 事务内登记，提交后投递。
     *
     * <p>不在事务中时退化为 {@link #sendReliable} 并打一条 warn——静默退化会让
     * "消息与业务数据原子"这个保证悄悄消失，而这种消失不会有任何症状，
     * 直到某次回滚之后消费端开始处理不存在的订单。
     */
    public void registerAfterCommit(String topic, String bizKey, Object body,
                                    LocalDateTime deliverTime) {
        MqMessage rec = build(topic, bizKey, body, deliverTime);
        mapper.insert(rec);

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("registerAfterCommit 不在事务中，退化为立即发送 topic={} bizKey={}",
                    topic, bizKey);
            trySend(rec);
            return;
        }
        Runnable task = () -> trySend(rec);
        Runnable traced = TraceContext.wrap(task);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        traced.run();
                    }
                });
    }

    /**
     * 发送并把状态推进到"已发送"。重发任务直接调它。
     *
     * @throws RuntimeException 发送失败原样抛出，由调用方决定是退避重试还是放弃回补
     */
    public void doSend(MqMessage rec) {
        sender.send(rec.getTopic(), rec.getBizKey(), rec.getBody(), rec.getDeliverTime());
        mapper.markSent(rec.getMsgId());
        log.info("stage=MQ_SEND msgId={} bizKey={} topic={} status=OK deliverTime={}",
                rec.getMsgId(), rec.getBizKey(), rec.getTopic(), rec.getDeliverTime());
    }

    private void trySend(MqMessage rec) {
        try {
            doSend(rec);
        } catch (Exception e) {
            log.warn("stage=MQ_SEND msgId={} bizKey={} topic={} status=FAIL 转由重发任务处理",
                    rec.getMsgId(), rec.getBizKey(), rec.getTopic(), e);
            markRetryQuietly(rec, e);
        }
    }

    /**
     * 首发失败时把原因记进表里。
     *
     * <p>不记的话 {@code last_error} 为空，排查只能翻应用日志，而日志可能已经滚掉了。
     * 这一步本身失败不能再抛：连不上 DB 的话上一步的 insert 就已经失败了，
     * 走不到这里；能走到这里说明是发送侧的问题，不该被记账失败掩盖成另一个异常。
     */
    private void markRetryQuietly(MqMessage rec, Exception cause) {
        try {
            mapper.markRetry(rec.getMsgId(), FIRST_BACKOFF_SECONDS, truncate(cause.getMessage()));
        } catch (Exception ignored) {
            log.warn("记录发送失败原因失败 msgId={}", rec.getMsgId());
        }
    }

    private MqMessage build(String topic, String bizKey, Object body,
                            LocalDateTime deliverTime) {
        return MqMessage.builder()
                .msgId(IdGenerator.msgId())
                .topic(topic)
                .bizKey(bizKey)
                .body(JsonUtil.toJson(body))
                .deliverTime(deliverTime)
                .status(MqStatus.PENDING.code())
                .sendCount(0)
                .nextRetryAt(LocalDateTime.now().plusSeconds(FIRST_BACKOFF_SECONDS))
                .build();
    }

    /** {@code last_error} 是 VARCHAR(500)，超长会让整条 UPDATE 失败 */
    public static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 480 ? s : s.substring(0, 480) + "...";
    }
}

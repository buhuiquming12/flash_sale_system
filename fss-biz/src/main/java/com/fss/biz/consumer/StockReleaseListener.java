package com.fss.biz.consumer;

import com.fss.biz.seckill.core.SeckillExecutor;
import com.fss.common.trace.TraceContext;
import com.fss.common.util.JsonUtil;
import com.fss.domain.mapper.MqMessageMapper;
import com.fss.domain.message.StockReleaseMessage;
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
 * 库存释放消费者：把已取消订单占用的那一份库存还回 <b>Redis</b>。
 *
 * <h3>为什么只还 Redis，DB 那半留在事务里</h3>
 * 这里和设计文档（docs/05）有一处刻意的偏差。文档把整个"取消回补"都改成消息，
 * 实现只把 Redis 那半挪了出来，DB 那半仍与关单同事务。理由是两半的性质完全不同：
 * <ul>
 *   <li><b>DB 回补能和关单同事务，所以必须同事务。</b> 同一个库、两条 UPDATE，
 *       原子性是免费的。拆成消息反而引入一个新的失败模式：消费端永久失败时，
 *       订单已经是 CANCELLED 却永远拿不回库存——而"已取消"没法回滚成"待支付"。
 *       同事务下回补失败则关单一起回滚，订单留在待支付由扫描重试，一份也不会丢。</li>
 *   <li><b>Redis 回补没法加入 DB 事务，所以必须可重试。</b> 阶段二的做法是
 *       提交后直接 INCRBY，失败只打日志、等阶段四对账——那期间 Redis 少一份库存，
 *       是实打实的少卖。改成消息之后它有了 5 次退避重试与死信兜底，
 *       这才是 {@code FSS_STOCK_RELEASE} 这个 Topic 真正的用处。</li>
 * </ul>
 *
 * <p>幂等靠脚本 C 里的 {@code SADD released orderNo} 返回值，不靠这里判断。
 * 重复投递 10 次也只会 {@code INCRBY} 一次。
 */
@Slf4j
@Component
@Profile("consumer")
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.STOCK_RELEASE,
        consumerGroup = MqTopics.GID_STOCK_RELEASE,
        consumeMode = ConsumeMode.CONCURRENTLY,
        consumeThreadNumber = 4,
        maxReconsumeTimes = 5)
public class StockReleaseListener implements RocketMQListener<MessageExt> {

    private final SeckillExecutor  executor;
    private final MqMessageMapper  mqMapper;

    @Override
    public void onMessage(MessageExt ext) {
        String body = new String(ext.getBody(), StandardCharsets.UTF_8);
        StockReleaseMessage msg;
        try {
            msg = JsonUtil.parse(body, StockReleaseMessage.class);
        } catch (Exception e) {
            log.error("stage=STOCK_RELEASE_MQ result=UNPARSEABLE keys={} body={}",
                    ext.getKeys(), body, e);
            return;
        }

        TraceContext.set(msg.getTraceId());
        try {
            // executor.release 内部把 Redis 异常吞掉并返回 false —— 那是给
            // "提交后回调"用的语义（那里抛了也无处可去）。消费端要的是相反的：
            // 失败必须让 MQ 重投，所以这里显式检查 Redis 里的结果
            boolean done = executor.release(msg.getActivityId(), msg.getSkuId(),
                    msg.getOrderNo(), msg.getQuantity());
            if (!done && !alreadyReleased(msg)) {
                throw new IllegalStateException(
                        "Redis 库存回补未生效: orderNo=" + msg.getOrderNo());
            }
            log.info("stage=STOCK_RELEASE_MQ orderNo={} qty={} done={} reconsume={}",
                    msg.getOrderNo(), msg.getQuantity(), done, ext.getReconsumeTimes());
            markConsumed(ext);

        } catch (Exception e) {
            log.error("stage=STOCK_RELEASE_MQ orderNo={} result=ERROR reconsume={} 触发重试",
                    msg.getOrderNo(), ext.getReconsumeTimes(), e);
            throw new IllegalStateException("库存回补失败，待重试: " + msg.getOrderNo(), e);
        } finally {
            TraceContext.clear();
        }
    }

    /**
     * 区分"回补失败"与"早就回补过了"。
     *
     * <p>脚本 C 对两者都返回非 0：幂等命中返回 1，而 Redis 抛异常时
     * {@code executor.release} 返回 false。前者是正常的重复投递，
     * 必须 ACK；后者必须重试。分不开的话，重复投递会被当成失败无限重试，
     * 最后整批进死信 —— 一个纯粹由"把幂等命中误判成失败"造出来的故障。
     */
    private boolean alreadyReleased(StockReleaseMessage msg) {
        return executor.isReleased(msg.getActivityId(), msg.getSkuId(), msg.getOrderNo());
    }

    private void markConsumed(MessageExt ext) {
        try {
            mqMapper.markConsumedByBizKey(ext.getKeys(), MqTopics.STOCK_RELEASE);
        } catch (Exception e) {
            log.debug("标记消息已消费失败 keys={}", ext.getKeys(), e);
        }
    }
}

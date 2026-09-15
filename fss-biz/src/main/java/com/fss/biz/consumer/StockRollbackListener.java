package com.fss.biz.consumer;

import com.fss.biz.seckill.core.RollbackOutcome;
import com.fss.biz.seckill.core.SeckillCompensateService;
import com.fss.common.error.ErrorCode;
import com.fss.common.trace.TraceContext;
import com.fss.common.util.JsonUtil;
import com.fss.domain.mapper.MqMessageMapper;
import com.fss.domain.message.OrderCreateMessage;
import com.fss.domain.message.StockRollbackMessage;
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
 * 补偿回补消费者（ROLLBACK 语义）。
 *
 * <p>消费端与死信处理器在自己的线程里就能直接调
 * {@link SeckillCompensateService#rollback}，所以这个 Topic 存在的意义只有一个：
 * <b>让"需要回补"这件事本身变得可靠</b>。回补动作要动 Redis，
 * 而 Redis 可能正好也不可用（F1）——那种时候直接调用只能记一条日志然后放弃，
 * 走消息则会重试 5 次再进死信。
 *
 * <p>幂等靠脚本 B 的请求状态机（{@code status ~= 0 → return 1}），
 * 重复投递 10 次 {@code INCRBY} 只执行一次（故障用例 F11）。
 *
 * <p><b>重要：当前没有任何代码往这个 Topic 发消息。</b>
 * 补偿走的是同步直调 + 库存对账兜底（见 README「已知取舍」）。本消费者保留下来
 * 是为了让 F11 这条演练仍可执行——用 {@code mqadmin sendMessage} 手动重投即可。
 * 排查问题时不要去找生产端，它不存在。
 */
@Slf4j
@Component
@Profile("consumer")
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.STOCK_ROLLBACK,
        consumerGroup = MqTopics.GID_STOCK_ROLLBACK,
        consumeMode = ConsumeMode.CONCURRENTLY,
        consumeThreadNumber = 4,
        maxReconsumeTimes = 5)
public class StockRollbackListener implements RocketMQListener<MessageExt> {

    private final SeckillCompensateService compensateService;
    private final MqMessageMapper          mqMapper;

    @Override
    public void onMessage(MessageExt ext) {
        String body = new String(ext.getBody(), StandardCharsets.UTF_8);
        StockRollbackMessage msg;
        try {
            msg = JsonUtil.parse(body, StockRollbackMessage.class);
        } catch (Exception e) {
            log.error("stage=STOCK_ROLLBACK_MQ result=UNPARSEABLE keys={} body={}",
                    ext.getKeys(), body, e);
            return;
        }

        TraceContext.set(msg.getTraceId());
        try {
            // 错误码从消息里带过来的 keepBought / failStatus 反推，
            // 而不是让消费端自己猜：判断"该不该还资格"的上下文只有生产者有
            ErrorCode ec = Boolean.TRUE.equals(msg.getKeepBought())
                    ? ErrorCode.ALREADY_BOUGHT
                    : ErrorCode.SYSTEM_BUSY;

            RollbackOutcome outcome = compensateService.rollback(toCreateMessage(msg), ec,
                    msg.getReason());
            // FAILED 必须重投：Redis 调用失败时回补到底发生没有是未知的。
            // 早先这里只看 boolean 的 false，而 false 同时表示"幂等命中"，
            // 于是回补失败也会走进 markConsumed —— 与下面 catch 里写的"待重试"自相矛盾
            if (outcome.isFailed()) {
                throw new IllegalStateException("补偿回补未生效: " + msg.getRequestNo());
            }
            log.info("stage=STOCK_ROLLBACK_MQ requestNo={} outcome={} reconsume={}",
                    msg.getRequestNo(), outcome, ext.getReconsumeTimes());
            markConsumed(ext);

        } catch (Exception e) {
            log.error("stage=STOCK_ROLLBACK_MQ requestNo={} result=ERROR reconsume={} 触发重试",
                    msg.getRequestNo(), ext.getReconsumeTimes(), e);
            throw new IllegalStateException("补偿回补失败，待重试: " + msg.getRequestNo(), e);
        } finally {
            TraceContext.clear();
        }
    }

    private OrderCreateMessage toCreateMessage(StockRollbackMessage m) {
        return OrderCreateMessage.builder()
                .requestNo(m.getRequestNo())
                .userId(m.getUserId())
                .activityId(m.getActivityId())
                .skuId(m.getSkuId())
                .quantity(m.getQuantity())
                .traceId(m.getTraceId())
                .version(OrderCreateMessage.CURRENT_VERSION)
                .build();
    }

    private void markConsumed(MessageExt ext) {
        try {
            mqMapper.markConsumedByBizKey(ext.getKeys(), MqTopics.STOCK_ROLLBACK);
        } catch (Exception e) {
            log.debug("标记消息已消费失败 keys={}", ext.getKeys(), e);
        }
    }
}

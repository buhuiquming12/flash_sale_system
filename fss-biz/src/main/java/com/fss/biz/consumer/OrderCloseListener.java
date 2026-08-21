package com.fss.biz.consumer;

import com.fss.biz.order.service.OrderService;
import com.fss.common.trace.TraceContext;
import com.fss.common.util.JsonUtil;
import com.fss.domain.entity.Order;
import com.fss.domain.mapper.MqMessageMapper;
import com.fss.domain.mapper.OrderMapper;
import com.fss.domain.message.OrderCloseMessage;
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
import java.time.LocalDateTime;

/**
 * 超时关单消费者（定时消息，投递时刻 = 订单过期时刻）。
 *
 * <p>定时消息比"每 2 分钟扫一次全表"精确得多：订单在 15:00:00 过期就在 15:00:00 关，
 * 不会晚 2 分钟，也不需要每 2 分钟对 {@code t_order} 做一次范围查询。
 * 但它<b>不能替代</b>扫描任务——消息可能丢（broker 磁盘故障、Topic 被误删、
 * 5.x 时间轮在极端情况下漏投），而"订单永久待支付"是个不会自己暴露的故障：
 * 用户不会来投诉一张他早就忘了的订单，库存就那么一直被占着。
 * 两者的关系是主路径与兜底，不是二选一。
 *
 * <p><b>必须重新读 DB 判状态，不能信消息。</b> 用户可能在过期前 1 秒付了款，
 * 这条消息仍然会按时投递。关单走的是 {@code WHERE status = 0} 条件更新，
 * 已支付的订单影响行数为 0，天然不会被误关——但那是 SQL 层的保证，
 * 这里先读一次能避免对已经终态的订单白起一个事务。
 */
@Slf4j
@Component
@Profile("consumer")
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.ORDER_CLOSE,
        consumerGroup = MqTopics.GID_ORDER_CLOSE,
        consumeMode = ConsumeMode.CONCURRENTLY,
        consumeThreadNumber = 4,
        maxReconsumeTimes = 5)
public class OrderCloseListener implements RocketMQListener<MessageExt> {

    private final OrderService    orderService;
    private final OrderMapper     orderMapper;
    private final MqMessageMapper mqMapper;

    @Override
    public void onMessage(MessageExt ext) {
        String body = new String(ext.getBody(), StandardCharsets.UTF_8);
        OrderCloseMessage msg;
        try {
            msg = JsonUtil.parse(body, OrderCloseMessage.class);
        } catch (Exception e) {
            log.error("stage=ORDER_CLOSE result=UNPARSEABLE keys={} body={}",
                    ext.getKeys(), body, e);
            return;
        }

        TraceContext.set(msg.getTraceId());
        try {
            Order order = orderMapper.selectByOrderNo(msg.getOrderNo());
            if (order == null) {
                // 订单不存在：消息比订单事务的提交更早到达是不可能的
                // （registerAfterCommit 保证提交后才投递），所以这只可能是
                // 脏数据或人工删过订单。ACK 掉并留一条 warn
                log.warn("stage=ORDER_CLOSE orderNo={} result=ORDER_NOT_FOUND",
                        msg.getOrderNo());
                markConsumed(ext);
                return;
            }
            // 提前判一次终态：绝大多数订单在过期前就已支付或取消，
            // 这一次读能省掉一个空转的写事务
            if (order.getStatus() != 0) {
                log.info("stage=ORDER_CLOSE orderNo={} result=ALREADY_TERMINAL status={}",
                        msg.getOrderNo(), order.getStatus());
                markConsumed(ext);
                return;
            }
            // 过期时刻可能被延长过（比如运营手工延期），此时不该关
            if (order.getExpireTime() != null
                    && order.getExpireTime().isAfter(LocalDateTime.now())) {
                log.info("stage=ORDER_CLOSE orderNo={} result=NOT_EXPIRED_YET expireTime={}",
                        msg.getOrderNo(), order.getExpireTime());
                markConsumed(ext);
                return;
            }

            boolean closed = orderService.closeOrder(msg.getOrderNo(), "超时未支付(定时消息)");
            log.info("stage=ORDER_CLOSE orderNo={} closed={} reconsume={}",
                    msg.getOrderNo(), closed, ext.getReconsumeTimes());
            markConsumed(ext);

        } catch (Exception e) {
            // 关单失败必须重试：不重试的话这张订单就只能等 2 分钟一轮的扫描兜底，
            // 而扫描也可能因为同样的原因失败
            log.error("stage=ORDER_CLOSE orderNo={} result=ERROR reconsume={} 触发重试",
                    msg.getOrderNo(), ext.getReconsumeTimes(), e);
            throw new IllegalStateException("关单失败，待重试: " + msg.getOrderNo(), e);
        } finally {
            TraceContext.clear();
        }
    }

    private void markConsumed(MessageExt ext) {
        try {
            mqMapper.markConsumedByBizKey(ext.getKeys(), MqTopics.ORDER_CLOSE);
        } catch (Exception e) {
            log.debug("标记消息已消费失败 keys={}", ext.getKeys(), e);
        }
    }
}

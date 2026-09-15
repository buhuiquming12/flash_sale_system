package com.fss.biz.consumer;

import com.fss.biz.order.service.OrderCreateService;
import com.fss.biz.seckill.core.RollbackOutcome;
import com.fss.biz.seckill.core.SeckillCompensateService;
import com.fss.biz.seckill.core.SeckillExecutor;
import com.fss.common.enums.SeckillRequestStatus;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.common.trace.TraceContext;
import com.fss.common.util.JsonUtil;
import com.fss.domain.entity.Order;
import com.fss.domain.mapper.MqMessageMapper;
import com.fss.domain.message.OrderCreateMessage;
import com.fss.infra.alarm.AlarmService;
import com.fss.infra.metrics.SeckillMetrics;
import com.fss.infra.mq.MqTopics;
import com.fss.infra.tx.TxSupport;
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
 * 订单创建消费者 —— 异步化之后订单真正诞生的地方。
 *
 * <p>它<b>不含任何业务规则</b>，只做四件事：解析、版本校验、调
 * {@link OrderCreateService#handle}、按结果决定 ACK 还是重试。
 * 落库逻辑一行都没有搬过来，这是把异步化的风险限制在"谁来调用"而不是"怎么落库"
 * 的关键——阶段一二的 55 个落库用例仍然覆盖着同一段代码。
 *
 * <h3>抛异常 vs 补偿并 ACK，是这个类唯一的判断</h3>
 * <table>
 *   <tr><th>失败类型</th><th>例子</th><th>动作</th><th>为什么</th></tr>
 *   <tr><td>确定性</td><td>DB 库存真不足、{@code uk_activity_sku_user} 冲突</td>
 *       <td>回补 + ACK</td><td>重试 5 次也不会成功，白占 5 分钟库存</td></tr>
 *   <tr><td>可恢复</td><td>DB 连不上、超时、死锁</td>
 *       <td>抛出 → 重试</td><td>下一次可能就成了，回补反而让用户白丢资格</td></tr>
 *   <tr><td>重复投递</td><td>ACK 丢了、手动重投</td>
 *       <td>ACK</td><td>L1 幂等命中，订单已经在了</td></tr>
 * </table>
 * 把确定性失败也交给重试是很常见的写法，代价是库存被占满整个重试周期
 * （5 次退避 ≈ 数分钟），而且最后还是要在死信里回补——中间那几分钟纯亏。
 *
 * <p>{@code ConsumeMode.CONCURRENTLY}：秒杀订单之间没有顺序依赖。
 * 用顺序消费会把并发度锁死到队列数，且单条卡住会拖垮整个队列，
 * 而幂等设计已经让乱序无害。
 */
@Slf4j
@Component
@Profile("consumer")
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.ORDER_CREATE,
        consumerGroup = MqTopics.GID_ORDER_CREATE,
        consumeMode = ConsumeMode.CONCURRENTLY,
        // 与 Hikari 池大小对齐：2 实例 × 12 = 24 并发写 < 30 连接，
        // 留 6 个给定时任务和监控查询。设成 20（2×20=40 > 30）会让消费线程
        // 大量阻塞在获取连接上，症状是消费 TPS 上不去但 CPU 很闲，
        // 很容易被误判成 MQ 慢
        consumeThreadNumber = 12,
        maxReconsumeTimes = 5)
public class OrderCreateListener implements RocketMQListener<MessageExt> {

    private final OrderCreateService       orderCreateService;
    private final SeckillCompensateService compensateService;
    private final SeckillExecutor          executor;
    private final MqMessageMapper          mqMapper;
    private final SeckillMetrics           metrics;
    private final AlarmService             alarm;

    @Override
    public void onMessage(MessageExt ext) {
        String body = new String(ext.getBody(), StandardCharsets.UTF_8);
        OrderCreateMessage msg;
        try {
            msg = JsonUtil.parse(body, OrderCreateMessage.class);
        } catch (Exception e) {
            // 解析不了的消息重试一万次也解析不了，直接 ACK 并告警。
            // 抛出去只会让它在队列里转 5 圈再进死信，中间刷 5 遍同样的错误日志
            log.error("stage=ORDER_CREATE result=UNPARSEABLE keys={} body={}",
                    ext.getKeys(), body, e);
            return;
        }

        // MDC 跨 MQ 传递：不 put 的话消费端日志全都没有 traceId，
        // 而"用户有资格但没订单"的排查恰好要从秒杀提交一路追到这里
        TraceContext.set(msg.getTraceId());
        try {
            consume(msg, ext);
        } finally {
            TraceContext.clear();
        }
    }

    private void consume(OrderCreateMessage msg, MessageExt ext) {
        // 版本校验必须在业务处理之前。新版本消息里字段含义可能变了，
        // 用旧代码解析出来的可能是一张金额为 0 的订单——那比不处理危险得多
        if (msg.getVersion() == null || msg.getVersion() > OrderCreateMessage.CURRENT_VERSION) {
            log.error("stage=ORDER_CREATE requestNo={} result=UNKNOWN_VERSION version={} 进死信",
                    msg.getRequestNo(), msg.getVersion());
            throw new IllegalStateException("未知消息版本: " + msg.getVersion());
        }

        try {
            OrderCreateService.Created created = orderCreateService.handle(msg);
            Order order = created.order();

            // 埋点放在<b>回写结论之前</b>。回写是"让外部看到成功"的那一刻，
            // 之后指标才出现的话，任何观察者（看板、告警、测试）都有一个窗口
            // 能看到"订单已成功"而 fss_order_created_total 还没动。
            // 这个窗口只有几毫秒且指标是最终一致的，但顺序对了就不必解释它
            if (!created.duplicate()) {
                metrics.orderCreated(msg.getActivityId(), msg.getSkuId());
            }

            // 结论回写必须在<b>事务提交之后</b>。提交前写的话，客户端可能查到
            // "秒杀成功 + 订单号"，而那个事务随后回滚了——它拿着一个不存在的订单号
            writeSuccessAfterCommit(msg, order);
            markConsumed(ext);

            log.info("stage=ORDER_CREATE requestNo={} orderNo={} result={} reconsume={}",
                    msg.getRequestNo(), order.getOrderNo(),
                    created.duplicate() ? "DUPLICATE_ACK" : "OK", ext.getReconsumeTimes());

        } catch (BizException e) {
            ErrorCode ec = e.getErrorCode();
            if (ec == ErrorCode.REQUEST_DUPLICATED) {
                // 同一 requestNo 被并发处理，另一方已建单。当前事务在 REPEATABLE READ
                // 下看不到对方的提交，所以查也查不到那张订单——但它确实存在，
                // 结论会由赢家写进 Redis。直接 ACK
                log.info("stage=ORDER_CREATE requestNo={} result=CONCURRENT_DUPLICATE_ACK",
                        msg.getRequestNo());
                markConsumed(ext);
                return;
            }
            metrics.orderCreateFailed(msg.getActivityId(), msg.getSkuId(),
                    ec.name().toLowerCase());
            if (ec.isDeterministic()) {
                log.warn("stage=ORDER_CREATE requestNo={} result=DETERMINISTIC_FAIL code={} 立即回补",
                        msg.getRequestNo(), ec.name());
                RollbackOutcome outcome = compensateService.rollback(msg, ec, ec.getMessage());
                if (outcome.isFailed()) {
                    // 确定性失败本来就是为了"尽快把库存还回去"才就地回补的，
                    // 结果回补自己失败了 —— 这次没有 MQ 重试兜着（消息已经 ACK），
                    // 只能靠库存对账发现，所以按 P1 报出来
                    log.error("stage=ORDER_CREATE requestNo={} result=ROLLBACK_FAILED 库存泄漏",
                            msg.getRequestNo());
                    alarm.p1(AlarmService.Event.REDIS_UNCERTAIN, msg.getRequestNo(),
                            "确定性失败后立即回补失败，库存可能泄漏");
                }
                markConsumed(ext);
                return;
            }
            // 非确定性的业务异常（比如活动被临时关闭又开回来）：交给重试
            log.error("stage=ORDER_CREATE requestNo={} result=RETRYABLE_BIZ_FAIL code={} reconsume={}",
                    msg.getRequestNo(), ec.name(), ext.getReconsumeTimes(), e);
            throw e;

        } catch (Exception e) {
            // DB 连不上、超时、死锁 —— 下一次可能就成了。抛出触发重试；
            // 5 次耗尽后进死信，由死信消费者回补，不会永久占着库存
            metrics.orderCreateFailed(msg.getActivityId(), msg.getSkuId(), "error");
            log.error("stage=ORDER_CREATE requestNo={} result=ERROR reconsume={} 触发重试",
                    msg.getRequestNo(), ext.getReconsumeTimes(), e);
            throw new IllegalStateException("落库失败，待重试: " + msg.getRequestNo(), e);
        }
    }

    private void writeSuccessAfterCommit(OrderCreateMessage msg, Order order) {
        TxSupport.afterCommit("writeSeckillResult:" + msg.getRequestNo(),
                () -> executor.writeResult(msg.getActivityId(), msg.getSkuId(),
                        msg.getRequestNo(), msg.getUserId(),
                        SeckillRequestStatus.SUCCESS, order.getOrderNo(), null));
    }

    /**
     * 把本地消息表的记录标成"已消费"。
     *
     * <p>这一步<b>不影响正确性</b>，只是让本地消息表能被安全归档：状态 2 的记录
     * 7 天后可删。所以失败只记 debug——为了一条归档标记去重试整条消息，
     * 会让一条已经成功落库的请求被重复处理（虽然幂等能挡住，但纯属白费）。
     */
    private void markConsumed(MessageExt ext) {
        try {
            mqMapper.markConsumedByBizKey(ext.getKeys(), MqTopics.ORDER_CREATE);
        } catch (Exception e) {
            log.debug("标记消息已消费失败 keys={}", ext.getKeys(), e);
        }
    }
}

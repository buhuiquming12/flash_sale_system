package com.fss.biz.order.service;

import com.fss.biz.mq.ReliableMqProducer;
import com.fss.common.enums.StockChangeType;
import com.fss.common.error.Assert;
import com.fss.common.error.ErrorCode;
import com.fss.common.trace.TraceContext;
import com.fss.domain.entity.Order;
import com.fss.domain.entity.SeckillGoods;
import com.fss.domain.entity.StockLog;
import com.fss.domain.mapper.OrderMapper;
import com.fss.domain.mapper.SeckillGoodsMapper;
import com.fss.domain.mapper.StockLogMapper;
import com.fss.domain.message.StockReleaseMessage;
import com.fss.infra.mq.MqTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存释放（取消回补，RELEASE 语义）。
 *
 * <p><b>只归还库存，保留用户购买标记。</b> 这是与"补偿回补"（ROLLBACK）的关键区别：
 * <table>
 *   <tr><th>语义</th><th>触发场景</th><th>库存</th><th>用户资格</th></tr>
 *   <tr><td>RELEASE</td><td>超时关闭 / 主动取消 / 支付失败</td><td>+qty</td><td><b>保留</b></td></tr>
 *   <tr><td>ROLLBACK</td><td>预扣成功但订单最终没能创建</td><td>+qty</td><td>归还</td></tr>
 * </table>
 * 取消后不允许重抢（决策 1），所以这里绝不能动用户标记——否则用户重抢时
 * Redis 放行、消费端撞 {@code uk_activity_sku_user}（已取消的订单仍占着唯一键）、
 * 于是又回补，形成死循环。
 *
 * <p>三层幂等，叠加是因为失效场景不同：
 * <ol>
 *   <li>{@code stock_released} 条件更新：拿到"我是唯一执行者"的许可</li>
 *   <li>{@code uk_biz_type} 唯一键：读改写有并发窗口，唯一键插入是数据库层串行化</li>
 *   <li>Redis {@code released} Set：防"DB 事务提交了但 Redis 回补重试"——
 *       前两层都在 DB 里，管不住 Redis 侧的重复 INCRBY</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReleaseService {

    private final OrderMapper        orderMapper;
    private final SeckillGoodsMapper goodsMapper;
    private final StockLogMapper     stockLogMapper;
    private final ReliableMqProducer producer;

    /**
     * 释放某订单占用的库存。
     *
     * <p>用默认的 {@code REQUIRED} 传播级别，<b>与关单同事务</b>。
     * 曾考虑过 {@code REQUIRES_NEW}（"回补失败不该把已关的订单回滚"），但那是错的：
     * 回补先独立提交、外层关单随后回滚，就会出现 {@code stock_released = 1} 且库存已
     * 归还、而订单还是待支付——用户仍能支付这张单，等于超卖。
     * 同事务下回补失败则关单一起回滚，订单留在待支付，由超时扫描重试，没有泄漏。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean release(String orderNo, String reason) {
        // 幂等 1：条件更新把自己变成唯一执行者
        int rows = orderMapper.markStockReleased(orderNo);
        if (rows == 0) {
            log.info("stage=STOCK_RELEASE orderNo={} idempotent=true", orderNo);
            return false;
        }

        Order order = orderMapper.selectByOrderNo(orderNo);
        Assert.requireFound(order, ErrorCode.ORDER_NOT_FOUND);

        SeckillGoods goods = goodsMapper.selectByActivitySku(
                order.getActivityId(), order.getSkuId());
        Assert.requireFound(goods, ErrorCode.GOODS_NOT_FOUND);

        // 幂等 2：流水唯一键
        try {
            stockLogMapper.insert(StockLog.of(orderNo, StockChangeType.CANCEL_RELEASE,
                    order.getQuantity(), order.getActivityId(), order.getSkuId(),
                    goods.getAvailableStock(), goods.getAvailableStock() + order.getQuantity(),
                    "system", reason));
        } catch (DuplicateKeyException e) {
            log.warn("stage=STOCK_RELEASE orderNo={} idempotent=true reason=dup_stock_log", orderNo);
            return false;
        }

        int restored = goodsMapper.restoreStock(
                order.getActivityId(), order.getSkuId(), order.getQuantity());
        if (restored == 0) {
            // locked_stock 不足以扣减 —— 说明库存账目已经错乱，必须让事务回滚并告警，
            // 而不是"回补失败但订单已标记 released"，那会永久少一份库存
            throw new IllegalStateException(
                    "库存回补失败，locked_stock 不足: orderNo=" + orderNo);
        }

        releaseRedisAfterCommit(order, reason);

        log.info("stage=STOCK_RELEASE orderNo={} qty={} idempotent=false reason={}",
                orderNo, order.getQuantity(), reason);
        return true;
    }

    /**
     * Redis 库存回补：登记一条 {@code FSS_STOCK_RELEASE}，事务提交后投递。
     *
     * <h3>为什么 DB 那半留在事务里、Redis 这半走消息</h3>
     * 这里与设计文档（docs/05）有一处刻意偏差。文档把整个取消回补都改成消息，
     * 实现只把 Redis 那半挪了出来。两半的性质完全不同：
     * <ul>
     *   <li>DB 回补<b>能</b>和关单同事务，所以必须同事务。同一个库、两条 UPDATE，
     *       原子性是免费的。拆成消息反而引入新的失败模式：消费端永久失败时，
     *       订单已是 CANCELLED 却永远拿不回库存，而"已取消"没法回滚成"待支付"。</li>
     *   <li>Redis 回补<b>没法</b>加入 DB 事务，所以必须可重试。阶段二是提交后直接
     *       INCRBY，失败只打日志等对账——那期间 Redis 少一份库存，是实打实的少卖。
     *       登记成消息之后它有了 5 次退避重试与死信兜底。</li>
     * </ul>
     *
     * <p>顺序仍然是"提交之后"才投递。反了（事务内先投递、消费端抢先 INCRBY）会有
     * 一段时间：Redis 库存已经加回、别人已经能抢到这一份，而 DB 事务还没提交甚至
     * 可能回滚——回滚之后订单还是待支付、DB 库存没还，Redis 却多放出去一个资格，
     * 就是超卖。{@code registerAfterCommit} 把消息行写在同一事务里、send 放在提交后，
     * 两个方向都占住了。
     */
    private void releaseRedisAfterCommit(Order order, String reason) {
        producer.registerAfterCommit(MqTopics.STOCK_RELEASE, order.getOrderNo(),
                StockReleaseMessage.builder()
                        .orderNo(order.getOrderNo())
                        .activityId(order.getActivityId())
                        .skuId(order.getSkuId())
                        .quantity(order.getQuantity())
                        .reason(reason)
                        .traceId(TraceContext.get())
                        .version(StockReleaseMessage.CURRENT_VERSION)
                        .build(),
                null);
    }
}

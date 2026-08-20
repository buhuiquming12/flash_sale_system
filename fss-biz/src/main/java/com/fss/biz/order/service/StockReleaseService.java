package com.fss.biz.order.service;

import com.fss.biz.seckill.core.SeckillExecutor;
import com.fss.common.enums.StockChangeType;
import com.fss.common.error.Assert;
import com.fss.common.error.ErrorCode;
import com.fss.domain.entity.Order;
import com.fss.domain.entity.SeckillGoods;
import com.fss.domain.entity.StockLog;
import com.fss.domain.mapper.OrderMapper;
import com.fss.domain.mapper.SeckillGoodsMapper;
import com.fss.domain.mapper.StockLogMapper;
import com.fss.infra.tx.TxSupport;
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
    private final SeckillExecutor    executor;

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

        releaseRedisAfterCommit(order);

        log.info("stage=STOCK_RELEASE orderNo={} qty={} idempotent=false reason={}",
                orderNo, order.getQuantity(), reason);
        return true;
    }

    /**
     * Redis 库存回补必须在<b>事务提交之后</b>。
     *
     * <p>顺序反了（事务内先 INCRBY Redis）会有一段时间：Redis 库存已经加回、
     * 别人已经能抢到这一份，而 DB 事务还没提交甚至可能回滚——回滚之后
     * 订单还是待支付、DB 库存没还，但 Redis 已经多放出去一个资格，就是超卖。
     *
     * <p>放在提交后的代价是：提交成功而 Redis 回补失败时，Redis 少一份库存（少卖）。
     * 这个方向是安全的，而且脚本 C 的 {@code released} Set 让重试天然幂等，
     * 阶段四的库存对账会把它收敛回来。
     */
    private void releaseRedisAfterCommit(Order order) {
        TxSupport.afterCommit("releaseRedisStock:" + order.getOrderNo(),
                () -> executor.release(order.getActivityId(), order.getSkuId(),
                        order.getOrderNo(), order.getQuantity()));
    }
}

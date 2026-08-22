package com.fss.biz.reconcile;

import com.fss.common.enums.OrderStatus;
import com.fss.domain.dto.PaymentDiff;
import com.fss.domain.mapper.OrderMapper;
import com.fss.domain.mapper.SeckillGoodsMapper;
import com.fss.infra.metrics.SeckillMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付对账里唯一可以自动修的那一步。
 *
 * <h3>为什么单独一个类</h3>
 * 它必须带事务，而 {@link PaymentReconciler} 是在自己的循环里调它——
 * 同类内的自调用<b>绕过 Spring 代理</b>，{@code @Transactional} 会静默失效。
 * 那样的话"改订单状态"和"locked → sold"就成了两个独立事务，
 * 前者成功后者失败时留下一个库存对账也发现不了的不一致（等式 1、2 都仍然成立，
 * 只是 {@code locked} 里永久留着一份已经卖掉的量）。
 *
 * <p>这个坑在本项目里出现过第二次了（{@code OrderServiceImpl.doClose} 那里是用
 * 私有方法避开的）。区别在于那里两个调用方都在同一个事务边界内，
 * 而这里的调用方是一个<b>不该有事务</b>的循环——整轮对账包在一个事务里的话，
 * 一条差异修失败会把前面几百条修好的全部回滚。所以只能把事务边界放在单条差异上，
 * 也就只能拆成两个 bean。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentFixer {

    private final OrderMapper        orderMapper;
    private final SeckillGoodsMapper goodsMapper;
    private final SeckillMetrics     metrics;

    /**
     * A 类差异：支付成功但订单仍是待支付 → 补推订单状态并完成 {@code locked → sold}。
     *
     * @return 1 表示已补推；0 表示订单状态在这一瞬间变了，交给下一轮重新判定
     */
    @Transactional(rollbackFor = Exception.class)
    public int fixPaidOrderPending(PaymentDiff d) {
        int rows = orderMapper.markPaid(d.getOrderNo(),
                OrderStatus.PENDING_PAY.code(), OrderStatus.PAID.code());
        if (rows == 0) {
            // 真实支付回调刚到、或关单抢先。下一轮它会落到正确的类别里
            log.info("stage=RECONCILE_PAY orderNo={} result=STATUS_CHANGED 交给下一轮",
                    d.getOrderNo());
            return 0;
        }
        int moved = goodsMapper.moveLockedToSold(
                d.getActivityId(), d.getSkuId(), d.getQuantity());
        if (moved == 0) {
            // locked 不足以扣减 → 库存账目本身已经错乱。必须整体回滚：
            // 留下"订单已支付但库存没从 locked 挪走"比不修更难查
            throw new IllegalStateException(
                    "补推支付失败，locked_stock 不足: orderNo=" + d.getOrderNo());
        }
        metrics.orderPaid(d.getActivityId(), d.getSkuId());
        return 1;
    }
}

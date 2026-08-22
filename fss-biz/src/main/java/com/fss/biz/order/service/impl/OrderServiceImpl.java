package com.fss.biz.order.service.impl;

import com.fss.biz.order.core.OrderStateMachine;
import com.fss.biz.order.model.OrderVO;
import com.fss.biz.order.service.OrderService;
import com.fss.biz.order.service.StockReleaseService;
import com.fss.common.enums.OrderStatus;
import com.fss.common.error.Assert;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.common.result.PageR;
import com.fss.domain.entity.Order;
import com.fss.domain.entity.OrderItem;
import com.fss.domain.mapper.OrderItemMapper;
import com.fss.domain.mapper.OrderMapper;
import com.fss.infra.metrics.SeckillMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper         orderMapper;
    private final OrderItemMapper     orderItemMapper;
    private final StockReleaseService stockReleaseService;
    private final SeckillMetrics      metrics;

    @Override
    public PageR<OrderVO> listMyOrders(long userId, Integer status, long page, long size) {
        long total = orderMapper.countUserOrders(userId, status);
        if (total == 0) {
            return PageR.empty(page, size);
        }
        List<Order> orders = orderMapper.selectUserOrders(userId, status, (page - 1) * size, size);
        List<String> orderNos = orders.stream().map(Order::getOrderNo).toList();

        // 一次查出全部明细，避免 N+1
        Map<String, List<OrderItem>> itemMap = orderItemMapper.selectByOrderNos(orderNos).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderNo));

        List<OrderVO> list = orders.stream()
                .map(o -> toVO(o, itemMap.getOrDefault(o.getOrderNo(), List.of())))
                .toList();
        return PageR.of(total, page, size, list);
    }

    @Override
    public OrderVO detail(String orderNo, long userId) {
        // 归属条件带进 SQL，不是查完再判断：两者对"不存在"和"不属于你"
        // 返回完全一致的结果与耗时，不泄漏订单号是否有效
        Order o = orderMapper.selectByOrderNoAndUser(orderNo, userId);
        Assert.requireFound(o, ErrorCode.ORDER_NOT_FOUND);
        return toVO(o, orderItemMapper.selectByOrderNo(orderNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String orderNo, long userId) {
        Order o = orderMapper.selectByOrderNoAndUser(orderNo, userId);
        Assert.requireFound(o, ErrorCode.ORDER_NOT_FOUND);

        OrderStatus from = OrderStatus.of(o.getStatus());
        if (from == OrderStatus.CANCELLED) {
            return;                       // 幂等：已取消视为成功
        }
        // 断言只是防御性编程，真正的并发保证是下面的条件更新
        OrderStateMachine.assertTransfer(from, OrderStatus.CANCELLED);

        if (!doClose(orderNo, "用户主动取消")) {
            // 竞态：条件更新没抢到，说明期间被支付了
            Order latest = orderMapper.selectByOrderNo(orderNo);
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    "订单状态已变更为「" + OrderStatus.of(latest.getStatus()).getDesc() + "」，无法取消");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean closeOrder(String orderNo, String reason) {
        return doClose(orderNo, reason);
    }

    /**
     * 关单的实际实现。
     *
     * <p>抽成私有方法而不是让 {@link #cancel} 直接调 {@link #closeOrder}：
     * 后者是自调用，绕过 Spring 代理，事务语义取决于外层方法有没有 {@code @Transactional}——
     * 这种"看着对、改一行就错"的写法不值得留在关键路径上。
     *
     * <p>与支付竞争同一行的 {@code WHERE status = 0}，MySQL 行锁保证只有一方
     * {@code rows == 1}。<b>关单成功才回补库存</b>——顺序反了会出现"库存回补了但
     * 订单还是待支付"，用户仍能支付，等于超卖。
     */
    private boolean doClose(String orderNo, String reason) {
        int rows = orderMapper.markCancelled(orderNo, reason);
        if (rows == 0) {
            log.info("stage=ORDER_CLOSE orderNo={} rows=0 result=SKIP", orderNo);
            return false;                 // 已支付或已取消，什么都不做
        }
        log.info("stage=ORDER_CLOSE orderNo={} from=0 to=2 rows={} reason={}",
                orderNo, rows, reason);

        // 阶段一直接同步回补；阶段三改为发 STOCK_RELEASE 消息（提交后投递）
        stockReleaseService.release(orderNo, reason);

        Order o = orderMapper.selectByOrderNo(orderNo);
        if (o != null) {
            metrics.orderCancelled(o.getActivityId(), o.getSkuId(), cancelReasonTag(reason));
        }
        return true;
    }

    /**
     * 取消原因归成三档。
     *
     * <p>指标标签不能直接用 {@code reason} 原文：它是自由文本（"超时未支付(扫描)"、
     * "超时未支付(定时消息)"、"消息进入死信队列，已自动回补"…），每种措辞都会
     * 变成一条新的时间序列。归档之后 Grafana 里"取消原因分布"这张图才有意义。
     */
    private static String cancelReasonTag(String reason) {
        if (reason == null) {
            return "system";
        }
        if (reason.contains("超时")) {
            return "timeout";
        }
        if (reason.contains("用户")) {
            return "user";
        }
        return "system";
    }

    private OrderVO toVO(Order o, List<OrderItem> items) {
        Long remain = null;
        if (o.getStatus() == OrderStatus.PENDING_PAY.code() && o.getExpireTime() != null) {
            long s = Duration.between(LocalDateTime.now(), o.getExpireTime()).getSeconds();
            remain = Math.max(0, s);
        }
        return OrderVO.builder()
                .orderNo(o.getOrderNo())
                .status(o.getStatus())
                .statusDesc(OrderStatus.of(o.getStatus()).getDesc())
                .totalAmount(o.getTotalAmount())
                .payAmount(o.getPayAmount())
                .quantity(o.getQuantity())
                .expireTime(o.getExpireTime())
                .payTime(o.getPayTime())
                .createTime(o.getCreateTime())
                .cancelReason(o.getCancelReason())
                .remainSeconds(remain)
                .items(items.stream()
                        .map(i -> OrderVO.ItemVO.builder()
                                .skuId(i.getSkuId())
                                .productTitle(i.getProductTitle())
                                .spec(i.getSpecSnapshot())
                                .unitPrice(i.getUnitPrice())
                                .quantity(i.getQuantity())
                                .image(i.getImageSnapshot())
                                .build())
                        .toList())
                .build();
    }
}

package com.fss.biz.order.core;

import com.fss.common.enums.OrderStatus;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.fss.common.enums.OrderStatus.CANCELLED;
import static com.fss.common.enums.OrderStatus.FINISHED;
import static com.fss.common.enums.OrderStatus.PAID;
import static com.fss.common.enums.OrderStatus.PENDING_PAY;
import static com.fss.common.enums.OrderStatus.REFUNDED;
import static com.fss.common.enums.OrderStatus.REFUNDING;

/**
 * 订单状态机。
 *
 * <pre>
 *   PENDING_PAY ──支付成功──▶ PAID ──发货完成──▶ FINISHED
 *        │                     │                   │
 *        └─超时/取消─▶ CANCELLED└─申请退款─▶ REFUNDING◀┘
 *                                              │
 *                                        ┌─────┴─────┐
 *                                   REFUNDED      PAID(退款被拒)
 * </pre>
 *
 * <p><b>但状态机断言只是防御性编程，不是并发正确性的来源。</b>
 * 真正保证并发安全的是所有状态变更都用带旧状态条件的 UPDATE
 * （{@code WHERE order_no = ? AND status = #{from}}）。断言能拦住"代码写错了"，
 * 拦不住"两个线程同时改"——后者只有数据库行锁能拦。
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(PENDING_PAY, EnumSet.of(PAID, CANCELLED));
        ALLOWED.put(PAID,        EnumSet.of(FINISHED, REFUNDING));
        ALLOWED.put(REFUNDING,   EnumSet.of(REFUNDED, PAID));   // 退款被拒回到已支付
        ALLOWED.put(FINISHED,    EnumSet.of(REFUNDING));
        ALLOWED.put(CANCELLED,   EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(REFUNDED,    EnumSet.noneOf(OrderStatus.class));
    }

    private OrderStateMachine() {
    }

    public static boolean canTransfer(OrderStatus from, OrderStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void assertTransfer(OrderStatus from, OrderStatus to) {
        if (!canTransfer(from, to)) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    "非法状态流转 " + from + " → " + to);
        }
    }

    public static Set<OrderStatus> allowedFrom(OrderStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    public static boolean isTerminal(OrderStatus s) {
        return ALLOWED.getOrDefault(s, Set.of()).isEmpty();
    }
}

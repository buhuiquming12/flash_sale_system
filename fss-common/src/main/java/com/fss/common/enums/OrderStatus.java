package com.fss.common.enums;

import lombok.Getter;

/** 订单状态。状态机的合法流转定义见 {@code OrderStateMachine}。 */
@Getter
public enum OrderStatus implements CodeEnum {

    PENDING_PAY(0, "待支付"),
    PAID(1, "已支付"),
    CANCELLED(2, "已取消"),
    FINISHED(3, "已完成"),
    REFUNDING(4, "退款中"),
    REFUNDED(5, "已退款");

    private final int    code;
    private final String desc;

    OrderStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @Override
    public int code() {
        return code;
    }

    public static OrderStatus of(int code) {
        return CodeEnum.of(OrderStatus.class, code);
    }

    /**
     * 占用库存的状态集合，用于库存对账等式
     * {@code total = available + 排队中 + 有效订单占用}。
     * 只有 CANCELLED 不占用（已通过 released_stock 回到 available）。
     */
    public boolean occupiesStock() {
        return this != CANCELLED;
    }
}

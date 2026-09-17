package com.fss.common.enums;

import lombok.Getter;

/** 对账任务类型。 */
@Getter
public enum ReconcileTaskType implements CodeEnum {

    QUALIFICATION(1, "资格对账"),
    STOCK(2, "库存对账"),
    PAYMENT(3, "支付对账"),
    ORDER(4, "订单对账");

    private final int    code;
    private final String desc;

    ReconcileTaskType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @Override
    public int code() {
        return code;
    }

    public static ReconcileTaskType of(int code) {
        return CodeEnum.of(ReconcileTaskType.class, code);
    }
}

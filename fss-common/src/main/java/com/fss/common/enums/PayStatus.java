package com.fss.common.enums;

import lombok.Getter;

/** 支付流水状态。 */
@Getter
public enum PayStatus implements CodeEnum {

    PENDING(0, "待支付"),
    SUCCESS(1, "支付成功"),
    FAILED(2, "支付失败"),
    REFUNDED(3, "已退款");

    private final int    code;
    private final String desc;

    PayStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @Override
    public int code() {
        return code;
    }

    public static PayStatus of(int code) {
        return CodeEnum.of(PayStatus.class, code);
    }
}

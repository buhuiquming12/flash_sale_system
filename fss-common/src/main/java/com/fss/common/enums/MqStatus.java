package com.fss.common.enums;

import lombok.Getter;

/** 本地消息表投递状态。 */
@Getter
public enum MqStatus implements CodeEnum {

    PENDING(0, "待发送"),
    SENT(1, "已发送"),
    CONSUMED(2, "已消费"),
    FAILED(3, "发送失败");

    private final int    code;
    private final String desc;

    MqStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @Override
    public int code() {
        return code;
    }

    public static MqStatus of(int code) {
        return CodeEnum.of(MqStatus.class, code);
    }
}

package com.fss.common.enums;

import lombok.Getter;

/** 对账任务处理状态。 */
@Getter
public enum ReconcileTaskStatus implements CodeEnum {

    PENDING(0, "待处理"),
    AUTO_FIXED(1, "自动修复成功"),
    NEED_MANUAL(2, "需人工处理"),
    MANUAL_DONE(3, "人工已处理"),
    IGNORED(4, "已忽略");

    private final int    code;
    private final String desc;

    ReconcileTaskStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @Override
    public int code() {
        return code;
    }

    public static ReconcileTaskStatus of(int code) {
        return CodeEnum.of(ReconcileTaskStatus.class, code);
    }
}

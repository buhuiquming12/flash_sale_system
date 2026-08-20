package com.fss.common.enums;

import lombok.Getter;

/** 活动预热状态。预热未完成的活动不会进入 RUNNING。 */
@Getter
public enum WarmupState implements CodeEnum {

    NONE(0, "未预热"),
    DOING(1, "预热中"),
    DONE(2, "预热完成"),
    FAILED(3, "预热失败");

    private final int    code;
    private final String desc;

    WarmupState(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @Override
    public int code() {
        return code;
    }

    public static WarmupState of(int code) {
        return CodeEnum.of(WarmupState.class, code);
    }
}

package com.fss.common.enums;

import lombok.Getter;

/** 秒杀活动状态。由定时任务按时间自动推进，不靠人工点击。 */
@Getter
public enum ActivityStatus implements CodeEnum {

    DRAFT(0, "待发布"),
    READY(1, "待开始"),
    RUNNING(2, "进行中"),
    ENDED(3, "已结束"),
    CLOSED(4, "已关闭");

    private final int    code;
    private final String desc;

    ActivityStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @Override
    public int code() {
        return code;
    }

    public static ActivityStatus of(int code) {
        return CodeEnum.of(ActivityStatus.class, code);
    }
}

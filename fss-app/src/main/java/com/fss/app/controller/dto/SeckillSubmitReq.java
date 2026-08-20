package com.fss.app.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class SeckillSubmitReq {

    @NotNull @Positive
    private Long    activityId;

    @NotNull @Positive
    private Long    skuId;

    /** 当前版本仅支持 1，传其他值直接拒绝而不是静默改成 1 */
    @NotNull @Min(1) @Max(1)
    private Integer quantity = 1;

    /**
     * 显式拒绝请求体中的 userId。
     *
     * <p>用 {@code @Null} 而不是"接收后忽略"是一个防御设计：明确告诉调用方
     * 这个字段由服务端决定。静默忽略会掩盖客户端 bug——调用方以为自己在替别人下单，
     * 实际被换成了 token 里的用户，等到出问题才发现。
     */
    @Null(message = "不允许指定 userId")
    private Long    userId;

    /** 秒杀令牌，阶段二启用 */
    private String  token;
}

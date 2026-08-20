package com.fss.biz.seckill.model;

import lombok.Data;

/**
 * 秒杀命令。userId 不在这里——它只从 JWT 取。
 *
 * <p>Web 层的请求 DTO 会显式用 {@code @Null} 拒绝请求体中的 userId 字段，
 * 而不是静默忽略。静默忽略会掩盖客户端 bug：调用方以为自己指定了用户，
 * 实际被服务端换掉了，等到出问题才发现。
 */
@Data
public class SeckillCmd {

    private Long    activityId;
    private Long    skuId;
    private Integer quantity;
    /** 秒杀令牌，阶段二启用 */
    private String  token;

    public static SeckillCmd of(long activityId, long skuId, int quantity) {
        SeckillCmd c = new SeckillCmd();
        c.activityId = activityId;
        c.skuId = skuId;
        c.quantity = quantity;
        return c;
    }
}

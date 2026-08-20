package com.fss.biz.seckill.core;

import com.fss.common.error.ErrorCode;

/**
 * Lua 脚本 A 的返回值。
 *
 * <p>把"整数码 → 业务错误码"的映射收在这一个地方。散在调用处的话，
 * 脚本改了返回码而某个调用点忘了跟着改，表现是用户看到一个毫不相关的错误提示——
 * 这种缺陷不会让任何测试变红。
 *
 * @param code        脚本返回码，0 表示已取得资格并完成预扣
 * @param remainStock 预扣后的剩余库存；失败时为 0，无意义
 */
public record SeckillOutcome(int code, long remainStock) {

    public static final int OK              = 0;
    public static final int NOT_WARMED      = -1;
    public static final int OFF_SALE        = -2;
    public static final int NOT_START       = -3;
    public static final int ENDED           = -4;
    public static final int LIMIT_EXCEEDED  = -5;
    public static final int STOCK_NOT_ENOUGH = -6;
    public static final int REQUEST_DUP     = -7;
    public static final int SOLD_OUT        = -8;

    public boolean qualified() {
        return code == OK;
    }

    /**
     * @return 对应的业务错误码；{@code code == 0} 时返回 {@code null}
     */
    public ErrorCode errorCode() {
        return switch (code) {
            case OK               -> null;
            case NOT_WARMED       -> ErrorCode.GOODS_NOT_WARMED;
            case OFF_SALE         -> ErrorCode.GOODS_OFF_SALE;
            case NOT_START        -> ErrorCode.ACTIVITY_NOT_START;
            case ENDED            -> ErrorCode.ACTIVITY_ENDED;
            case LIMIT_EXCEEDED   -> ErrorCode.ALREADY_BOUGHT;
            case STOCK_NOT_ENOUGH, SOLD_OUT -> ErrorCode.STOCK_NOT_ENOUGH;
            case REQUEST_DUP      -> ErrorCode.REQUEST_DUPLICATED;
            // 未知返回码当系统错误处理，绝不当成"通过"。
            // 默认放行的写法在脚本被改坏时会直接超卖
            default               -> ErrorCode.SYSTEM_ERROR;
        };
    }
}

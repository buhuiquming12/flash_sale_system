package com.fss.common.error;

import lombok.Getter;

import java.util.EnumSet;
import java.util.Set;

/**
 * 全局错误码。
 *
 * <p>分段：1xxx 通用 / 2xxx 活动商品 / 3xxx 秒杀 / 4xxx 订单 / 5xxx 支付 / 9xxx 系统。
 * 对应设计文档 docs/06-接口契约.md §2。
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "成功"),

    // ---- 1xxx 通用 ----
    PARAM_INVALID(1001, "参数校验失败"),
    UNAUTHORIZED(1002, "未登录或登录已失效"),
    FORBIDDEN(1003, "无权限"),
    RATE_LIMITED(1004, "请求过于频繁"),
    SYSTEM_BUSY(1005, "系统繁忙，请稍后重试"),
    SERVICE_DEGRADED(1006, "服务降级中，请稍后再试"),

    // ---- 2xxx 活动与商品 ----
    ACTIVITY_NOT_FOUND(2001, "活动不存在"),
    ACTIVITY_NOT_START(2002, "活动未开始"),
    ACTIVITY_ENDED(2003, "活动已结束"),
    GOODS_OFF_SALE(2004, "商品已停售"),
    GOODS_NOT_WARMED(2005, "活动未预热，请稍后再试"),
    ACTIVITY_NOT_READY(2006, "活动配置不合法"),
    /** 设计文档 §2 的 DETERMINISTIC 集合引用了该码，但错误码表漏列，此处补齐 */
    GOODS_NOT_FOUND(2007, "秒杀商品不存在"),
    SKU_NOT_FOUND(2008, "SKU 不存在或已下架"),
    PRODUCT_NOT_FOUND(2009, "商品不存在"),
    ACTIVITY_STATUS_ILLEGAL(2010, "活动状态不允许该操作"),

    // ---- 3xxx 秒杀 ----
    STOCK_NOT_ENOUGH(3001, "库存不足"),
    ALREADY_BOUGHT(3002, "您已参与过本次秒杀"),
    SECKILL_TOKEN_INVALID(3003, "秒杀令牌无效或已使用"),
    REQUEST_DUPLICATED(3004, "请求号重复"),
    REQUEST_NOT_FOUND(3005, "秒杀请求不存在"),

    // ---- 4xxx 订单 ----
    ORDER_NOT_FOUND(4001, "订单不存在"),
    ORDER_STATUS_ILLEGAL(4002, "订单状态不允许该操作"),
    ORDER_EXPIRED(4003, "订单已超时"),
    ORDER_NOT_OWNED(4004, "无权操作该订单"),

    // ---- 5xxx 支付 ----
    PAY_AMOUNT_MISMATCH(5001, "支付金额与订单不符"),
    PAY_ALREADY_DONE(5002, "订单已支付"),
    PAY_SIGN_INVALID(5003, "回调签名校验失败"),

    // ---- 9xxx 系统 ----
    SYSTEM_ERROR(9000, "系统内部错误");

    private final int    code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 确定性失败集合：重试也不会成功。
     *
     * <p>消费端遇到确定性失败必须<b>立即补偿回补</b>并 ACK，而不是抛异常触发 MQ 重试——
     * 白重试 5 次后进死信，中间这段时间库存一直被占用。
     * 其余异常（DB 连接失败、超时、未知异常）视为可恢复，抛出以触发重试。
     */
    private static final Set<ErrorCode> DETERMINISTIC = EnumSet.of(
            STOCK_NOT_ENOUGH, ALREADY_BOUGHT, GOODS_NOT_FOUND,
            ACTIVITY_ENDED, PARAM_INVALID);

    public boolean isDeterministic() {
        return DETERMINISTIC.contains(this);
    }
}

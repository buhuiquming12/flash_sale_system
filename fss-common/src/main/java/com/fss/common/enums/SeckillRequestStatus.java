package com.fss.common.enums;

import lombok.Getter;

/** 秒杀请求状态。同时用于 Redis {@code seckill:req} 与 {@code t_seckill_request}。 */
@Getter
public enum SeckillRequestStatus implements CodeEnum {

    /** 已预扣，等待落库 */
    QUEUEING(0, "排队中"),
    /** 订单已创建 */
    SUCCESS(1, "秒杀成功"),
    /** 库存耗尽 */
    STOCK_NOT_ENOUGH(2, "库存不足"),
    /** 一人一单拦截 */
    ALREADY_BOUGHT(3, "已参与过本次秒杀"),
    /** 消费端不可恢复失败 */
    CREATE_FAILED(4, "创建订单失败"),
    /** 已补偿回补，库存与资格均已归还 */
    COMPENSATED(5, "系统繁忙已退回");

    private final int    code;
    private final String desc;

    SeckillRequestStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @Override
    public int code() {
        return code;
    }

    public static SeckillRequestStatus of(int code) {
        return CodeEnum.of(SeckillRequestStatus.class, code);
    }

    public boolean isTerminal() {
        return this != QUEUEING;
    }
}

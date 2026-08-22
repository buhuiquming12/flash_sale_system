package com.fss.domain.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付对账查出的一条差异。
 *
 * <p>四类差异共用一个结构：不同查询填不同字段（比如 B 类没有 {@code payNo}，
 * 因为它的问题恰恰是"没有成功流水"）。差异类型不放在这里，由调用方按查询来源标注——
 * 让 SQL 自己写一个字符串常量列回来只会让"改了查询忘了改常量"成为可能。
 *
 * <p>用可变 POJO 而不是 record：MyBatis 的自动列映射走 setter，
 * record 需要额外配置构造器映射，而这里只是一个查询结果载体，不值得为它加配置。
 */
@Data
public class PaymentDiff {

    private String        payNo;
    private String        orderNo;
    private Long          userId;
    /** 流水金额 */
    private BigDecimal    amount;
    /** 订单应付金额 */
    private BigDecimal    orderAmount;
    /** {@link com.fss.common.enums.OrderStatus} */
    private Integer       orderStatus;
    /** {@link com.fss.common.enums.PayStatus} */
    private Integer       payStatus;
    private Long          activityId;
    private Long          skuId;
    private Integer       quantity;
    private LocalDateTime finishTime;
}

package com.fss.domain.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 关闭订单指令（定时消息，投递时刻 = 订单过期时刻）。
 *
 * <p>只带订单号与用户，<b>不带过期时刻</b>：消费端要重新读 DB 才能知道订单当前
 * 是什么状态，顺手就能拿到 expire_time。消息体里带一份只会多一个可能过期的副本，
 * 而它唯一的用途是判断"现在该不该关"——那个判断必须基于 DB 的最新状态，
 * 否则用户在过期前 1 秒付了款、消息按自己带的时刻把单关了。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCloseMessage implements Serializable {

    private String  orderNo;
    private Long    userId;
    private String  traceId;
    private Integer version;

    public static final int CURRENT_VERSION = 1;
}

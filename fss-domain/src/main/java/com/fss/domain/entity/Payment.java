package com.fss.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 支付流水。{@code uk_out_trade_no} 防止渠道重复回调建多条成功流水。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_payment")
public class Payment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String     payNo;
    /** 外部渠道流水号，模拟支付时创建阶段为 null（MySQL 唯一索引允许多个 NULL） */
    private String     outTradeNo;
    private String     orderNo;
    private Long       userId;
    private BigDecimal amount;
    /** 0=模拟 1=支付宝 2=微信 */
    private Integer    channel;
    /** {@link com.fss.common.enums.PayStatus} */
    private Integer    status;
    /** 回调原文，审计用 */
    private String     notifyBody;

    private LocalDateTime createTime;
    private LocalDateTime finishTime;
}

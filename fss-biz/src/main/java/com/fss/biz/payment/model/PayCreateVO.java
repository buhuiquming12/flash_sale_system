package com.fss.biz.payment.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class PayCreateVO {

    private String     payNo;
    private BigDecimal amount;
    /** 模拟支付页 */
    private String     payUrl;
}

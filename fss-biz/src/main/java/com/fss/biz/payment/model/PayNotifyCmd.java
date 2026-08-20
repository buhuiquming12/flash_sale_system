package com.fss.biz.payment.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** 模拟渠道回调。字段名参与签名，改名即改签名，需与 {@code PaySignUtil} 同步。 */
@Data
public class PayNotifyCmd {

    @NotBlank
    private String     payNo;

    @NotBlank
    private String     outTradeNo;

    @NotNull
    private BigDecimal amount;

    /** SUCCESS / FAILED */
    @NotBlank
    private String     status;

    @NotNull
    private Long       timestamp;

    @NotBlank
    private String     sign;
}

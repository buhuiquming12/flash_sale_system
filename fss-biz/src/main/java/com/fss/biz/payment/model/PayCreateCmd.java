package com.fss.biz.payment.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PayCreateCmd {

    @NotBlank(message = "订单号不能为空")
    private String orderNo;
}

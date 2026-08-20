package com.fss.biz.payment.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class PayStatusVO {

    private String        orderNo;
    private Integer       orderStatus;
    private String        orderStatusDesc;
    private Integer       payStatus;
    private String        payStatusDesc;
    private LocalDateTime payTime;
}

package com.fss.biz.payment.service;

import com.fss.biz.payment.model.PayCreateCmd;
import com.fss.biz.payment.model.PayCreateVO;
import com.fss.biz.payment.model.PayNotifyCmd;
import com.fss.biz.payment.model.PayStatusVO;

import java.util.Map;

public interface PaymentService {

    PayCreateVO create(PayCreateCmd cmd, long userId);

    /** 渠道回调。校验顺序：验签 → 时间戳 → 查流水 → 比金额 → 改状态 */
    void notify(PayNotifyCmd cmd, Map<String, String> rawParams);

    PayStatusVO status(String orderNo, long userId);

    /** 供模拟支付页生成一份合法签名，仅在演示环境暴露 */
    Map<String, String> mockSign(String payNo, String outTradeNo, String amount, String status);
}

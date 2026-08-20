package com.fss.app.controller;

import com.fss.biz.payment.model.PayCreateCmd;
import com.fss.biz.payment.model.PayCreateVO;
import com.fss.biz.payment.model.PayNotifyCmd;
import com.fss.biz.payment.model.PayStatusVO;
import com.fss.biz.payment.service.PaymentService;
import com.fss.common.context.UserContext;
import com.fss.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Profile("web")
@RestController
@RequestMapping("/api/pay")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/create")
    public R<PayCreateVO> create(@Valid @RequestBody PayCreateCmd cmd) {
        return R.ok(paymentService.create(cmd, UserContext.userId()));
    }

    /**
     * 渠道回调。走验签而非 JWT——真实渠道不会带用户 token。
     *
     * <p>原始参数单独传给 service 用于验签：签名是对"渠道实际发来的字段"计算的，
     * 用反序列化后的对象重建参数会因为默认值、字段顺序、数字格式化而算出不同的串。
     */
    @PostMapping("/notify")
    public R<Void> notifyPay(@Valid @RequestBody PayNotifyCmd cmd) {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("payNo", cmd.getPayNo());
        raw.put("outTradeNo", cmd.getOutTradeNo());
        raw.put("amount", cmd.getAmount().toPlainString());
        raw.put("status", cmd.getStatus());
        raw.put("timestamp", String.valueOf(cmd.getTimestamp()));
        paymentService.notify(cmd, raw);
        return R.ok();
    }

    @GetMapping("/status")
    public R<PayStatusVO> status(@RequestParam String orderNo) {
        return R.ok(paymentService.status(orderNo, UserContext.userId()));
    }
}

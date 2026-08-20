package com.fss.app.controller;

import com.fss.biz.payment.service.PaymentService;
import com.fss.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模拟支付辅助接口，供演示页面生成一份合法的回调参数。
 *
 * <p><b>它等价于把验签密钥交给客户端，绝不能出现在生产环境。</b>
 * 因此单独成类并用 {@code @Profile("dev")} 隔离——注解在类上才生效，
 * 写在方法上是无效的（Spring 只在 Bean 定义层面处理 {@code @Profile}），
 * 这是一个容易写错且不会报错的地方。
 */
@Profile("dev")
@RestController
@RequestMapping("/api/pay")
@RequiredArgsConstructor
public class MockPayController {

    private final PaymentService paymentService;

    @GetMapping("/mock-sign")
    public R<Map<String, String>> mockSign(@RequestParam String payNo,
                                          @RequestParam String amount,
                                          @RequestParam(defaultValue = "SUCCESS") String status) {
        return R.ok(paymentService.mockSign(payNo, null, amount, status));
    }
}

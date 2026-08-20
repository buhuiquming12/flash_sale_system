package com.fss.app.controller;

import com.fss.biz.order.model.OrderVO;
import com.fss.biz.order.service.OrderService;
import com.fss.common.context.UserContext;
import com.fss.common.result.PageR;
import com.fss.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Profile("web")
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/list")
    public R<PageR<OrderVO>> list(@RequestParam(required = false) Integer status,
                                  @RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "10") long size) {
        return R.ok(orderService.listMyOrders(
                UserContext.userId(), status, page, Math.min(size, 50)));
    }

    @GetMapping("/{orderNo}")
    public R<OrderVO> detail(@PathVariable String orderNo) {
        return R.ok(orderService.detail(orderNo, UserContext.userId()));
    }

    @PostMapping("/{orderNo}/cancel")
    public R<Map<String, Object>> cancel(@PathVariable String orderNo) {
        orderService.cancel(orderNo, UserContext.userId());
        // 取消后该用户不能再抢（决策 1），响应里明确告知，避免用户反复尝试
        return R.ok(Map.of(
                "cancelled", true,
                "notice", "订单已取消，库存已释放给其他用户；本次活动您已不能再次参与"));
    }
}

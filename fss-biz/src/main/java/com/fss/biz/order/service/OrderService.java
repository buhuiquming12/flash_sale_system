package com.fss.biz.order.service;

import com.fss.biz.order.model.OrderVO;
import com.fss.common.result.PageR;

public interface OrderService {

    PageR<OrderVO> listMyOrders(long userId, Integer status, long page, long size);

    OrderVO detail(String orderNo, long userId);

    /** 用户主动取消。只有待支付可取消 */
    void cancel(String orderNo, long userId);

    /**
     * 关单（超时或系统触发）。
     *
     * @return true = 本次调用真正把订单关掉了；false = 已被支付或已取消，无需处理
     */
    boolean closeOrder(String orderNo, String reason);
}

package com.fss.biz.order.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
public class OrderVO {

    private String        orderNo;
    private Integer       status;
    private String        statusDesc;
    private BigDecimal    totalAmount;
    private BigDecimal    payAmount;
    private Integer       quantity;
    private LocalDateTime expireTime;
    private LocalDateTime payTime;
    private LocalDateTime createTime;
    private String        cancelReason;
    /** 服务端计算的剩余支付秒数，客户端不用自己算（避免时钟问题） */
    private Long          remainSeconds;
    private List<ItemVO>  items;

    @Data
    @Builder
    @AllArgsConstructor
    public static class ItemVO {
        private Long       skuId;
        private String     productTitle;
        private String     spec;
        private BigDecimal unitPrice;
        private Integer    quantity;
        private String     image;
    }
}

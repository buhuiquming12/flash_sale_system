package com.fss.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单明细。全部字段是快照，商品改名改价下架都不影响历史订单展示。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_order_item")
public class OrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String     orderNo;
    private Long       skuId;
    private Long       productId;
    private String     productTitle;
    private String     specSnapshot;
    private String     imageSnapshot;
    private BigDecimal unitPrice;
    private Integer    quantity;

    private LocalDateTime createTime;
}

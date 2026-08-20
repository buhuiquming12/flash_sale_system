package com.fss.biz.product.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class SkuVO {

    private Long       skuId;
    private Long       productId;
    private String     productTitle;
    private String     spec;
    private BigDecimal price;
    private Integer    stock;
    private Integer    status;
    private String     image;
}

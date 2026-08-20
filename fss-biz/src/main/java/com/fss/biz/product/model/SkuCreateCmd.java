package com.fss.biz.product.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SkuCreateCmd {

    @NotNull @Positive
    private Long productId;

    @NotBlank(message = "规格描述不能为空")
    @Size(max = 200)
    private String spec;

    @Size(max = 1000)
    private String specJson;

    @NotNull
    @DecimalMin(value = "0.01", message = "日常售价必须大于 0")
    private BigDecimal price;

    @NotNull @Min(value = 0, message = "库存不能为负")
    private Integer stock;

    private Integer status = 1;
}

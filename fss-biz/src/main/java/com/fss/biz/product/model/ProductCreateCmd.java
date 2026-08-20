package com.fss.biz.product.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProductCreateCmd {

    @NotBlank(message = "商品标题不能为空")
    @Size(max = 200)
    private String title;

    @Size(max = 500)
    private String subTitle;

    private String detail;

    @Size(max = 500)
    private String mainImage;

    /** 0=下架 1=上架，默认上架 */
    private Integer status = 1;
}

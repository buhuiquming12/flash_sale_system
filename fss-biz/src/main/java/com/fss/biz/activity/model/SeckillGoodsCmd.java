package com.fss.biz.activity.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SeckillGoodsCmd {

    @NotNull @Positive
    private Long skuId;

    @NotNull
    @DecimalMin(value = "0.01", message = "秒杀价必须大于 0")
    private BigDecimal seckillPrice;

    @NotNull
    @Min(value = 1, message = "秒杀库存必须大于 0")
    private Integer totalStock;

    /**
     * 每人限购。当前版本固定为 1。
     *
     * <p>大于 1 时 {@code uk_activity_sku_user} 无法表达限购语义，需要引入
     * {@code t_user_purchase_quota}，见 docs/02 附录。这里用 {@code @Max(1)}
     * 硬性拒绝，而不是"接受了但只按 1 处理"——后者会让配置与实际行为不一致。
     */
    @NotNull @Min(1) @Max(value = 1, message = "当前版本限购必须为 1")
    private Integer limitPerUser = 1;
}

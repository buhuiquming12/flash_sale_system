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

/** SKU。秒杀绑定 SKU 而非 Product：同款手机不同颜色容量必须独立库存。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_sku")
public class Sku {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long          productId;
    private String        spec;
    private String        specJson;
    /** 日常售价，秒杀价不应高于此值 */
    private BigDecimal    price;
    private Integer       stock;
    /** 0=下架 1=上架 */
    private Integer       status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

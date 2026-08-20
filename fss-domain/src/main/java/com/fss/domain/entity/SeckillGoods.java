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

/**
 * 秒杀商品——核心热点表。
 *
 * <p>{@code available_stock} 的扣减一律走条件更新
 * {@code WHERE available_stock >= qty}，不用 {@code SELECT FOR UPDATE}
 * 也不用 {@code version} 乐观锁。{@code version} 字段保留但主链路不使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_seckill_goods")
public class SeckillGoods {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long       activityId;
    private Long       skuId;
    /** 秒杀价，服务端唯一价格来源。绝不采信请求体或消息体中的金额 */
    private BigDecimal seckillPrice;
    private Integer    totalStock;
    private Integer    availableStock;
    private Integer    lockedStock;
    private Integer    soldStock;
    private Integer    releasedStock;
    private Integer    limitPerUser;
    /** 0=停售 1=在售 2=售罄 */
    private Integer    status;
    /** 预留乐观锁，主链路不用 */
    private Integer    version;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static final int STATUS_OFF_SALE = 0;
    public static final int STATUS_ON_SALE  = 1;
    public static final int STATUS_SOLD_OUT = 2;
}

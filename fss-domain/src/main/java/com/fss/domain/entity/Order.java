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
 * 秒杀订单。
 *
 * <p>三个唯一键各拦一类异常：
 * <ul>
 *   <li>{@code uk_request_no} —— MQ 重复投递同一条消息、消费端重试</li>
 *   <li>{@code uk_activity_sku_user} —— Redis 数据丢失后用户重抢、两个不同
 *       request_no 同时到达。<b>这是"一人一单"的最终归属</b></li>
 *   <li>{@code uk_order_no} —— 订单号碰撞兜底</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_order")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String     orderNo;
    /** 秒杀请求号，消费端幂等键 */
    private String     requestNo;
    private Long       userId;
    private Long       activityId;
    private Long       skuId;
    /** {@link com.fss.common.enums.OrderStatus} */
    private Integer    status;
    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private Integer    quantity;
    /** 0=未释放 1=已释放。取消回补的第一层幂等 */
    private Integer    stockReleased;

    private LocalDateTime expireTime;
    private LocalDateTime payTime;
    private LocalDateTime cancelTime;
    private LocalDateTime finishTime;
    private String        cancelReason;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

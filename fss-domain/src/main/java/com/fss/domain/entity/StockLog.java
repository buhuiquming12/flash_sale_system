package com.fss.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fss.common.enums.StockChangeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 库存流水。
 *
 * <p>{@code uk_biz_type (biz_no, change_type)} 是库存幂等的关键：
 * 同一订单的"取消回补"只能记一条，重复的回补消息在 insert 时撞唯一键。
 *
 * <p>这比只依赖 {@code t_order.stock_released} 更强——后者的读改写有并发窗口
 * （两个线程同时读到 0），而唯一键插入是数据库层的原子串行化。实现里两者都用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_stock_log")
public class StockLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务号：request_no（扣减/补偿）或 order_no（取消回补） */
    private String  bizNo;
    private Long    activityId;
    private Long    skuId;
    /** {@link StockChangeType} */
    private Integer changeType;
    /** 正 = 增加，负 = 减少 */
    private Integer quantity;
    private Integer beforeStock;
    private Integer afterStock;
    private String  operator;
    private String  remark;

    private LocalDateTime createTime;

    public static StockLog of(String bizNo, StockChangeType type, int quantity,
                              long activityId, long skuId, Integer before, Integer after,
                              String operator, String remark) {
        return StockLog.builder()
                .bizNo(bizNo)
                .activityId(activityId)
                .skuId(skuId)
                .changeType(type.code())
                .quantity(quantity)
                .beforeStock(before)
                .afterStock(after)
                .operator(operator)
                .remark(remark)
                .build();
    }
}

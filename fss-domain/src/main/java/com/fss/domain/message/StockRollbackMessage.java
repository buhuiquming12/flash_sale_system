package com.fss.domain.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 补偿回补指令（ROLLBACK 语义：Redis 预扣成功但订单最终没能创建）。
 *
 * <p>{@code keepBought} 是这个消息体里唯一需要想清楚的字段：确定性失败
 * （DB 侧 {@code uk_activity_sku_user} 冲突）时必须为 true，否则
 * 用户重抢 → Redis 放行 → DB 又冲突 → 又回补，无限循环。
 *
 * <p>{@code failStatus} 让消费端知道该把请求置成哪个终态。不带它的话回补脚本
 * 只能一律写"系统繁忙已退回"，而真实原因可能是"您已参与过本次秒杀"——
 * 用户看到前者会不停重试。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockRollbackMessage implements Serializable {

    private String  requestNo;
    private Long    userId;
    private Long    activityId;
    private Long    skuId;
    private Integer quantity;
    private String  reason;
    /** true = 不归还用户购买资格 */
    private Boolean keepBought;
    /** {@link com.fss.common.enums.SeckillRequestStatus} 的 code */
    private Integer failStatus;
    private String  traceId;
    private Integer version;

    public static final int CURRENT_VERSION = 1;
}

package com.fss.domain.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 库存释放指令（RELEASE 语义：只还库存，保留用户购买资格）。
 *
 * <p>触发于订单超时关闭、用户主动取消、支付失败。
 * 与 {@link StockRollbackMessage} 的区别见 {@code StockReleaseService} 的类注释——
 * 搞混的后果不是"数字差一点"，而是死循环或超卖。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockReleaseMessage implements Serializable {

    private String  orderNo;
    private Long    activityId;
    private Long    skuId;
    private Integer quantity;
    private String  reason;
    private String  traceId;
    private Integer version;

    public static final int CURRENT_VERSION = 1;
}

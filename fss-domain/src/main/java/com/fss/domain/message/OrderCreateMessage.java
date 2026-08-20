package com.fss.domain.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 创建订单指令。
 *
 * <p>阶段一由秒杀服务直接构造并同步调用落库；阶段三起作为 RocketMQ 消息体，
 * 由消费端反序列化后调用同一个落库方法。<b>接口不变，只是调用方式从同步变异步。</b>
 *
 * <p><b>不含任何金额字段。</b> 消费端从 {@code t_seckill_goods.seckill_price} 读价格。
 * 消息体一旦包含金额，就成了客户端到订单金额之间的一条可篡改路径——消息可被伪造重放。
 * 价格必须由服务端在落库时刻从权威数据源读取。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreateMessage implements Serializable {

    /** 幂等键 */
    private String        requestNo;
    private Long          userId;
    private Long          activityId;
    private Long          skuId;
    private Integer       quantity;
    private LocalDateTime requestTime;
    private String        traceId;
    /**
     * 消息格式版本。
     *
     * <p>格式变更时，消费端拿到未知版本直接进死信并告警，
     * 而不是用错误的字段解析出一张金额可能是 0 的订单。
     */
    private Integer       version;

    public static final int CURRENT_VERSION = 1;
}

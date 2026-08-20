package com.fss.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 秒杀请求记录。
 *
 * <p>异步化后（阶段三）本表<b>不在主链路同步写</b>：主链路只写 Redis
 * {@code seckill:req}（30 分钟 TTL），本表由消费端在创建订单时一并写入。
 * Redis 中存在但本表没有的记录，就是"排队中但消息丢失"的可疑请求，由对账任务捞出。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_seckill_request")
public class SeckillRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String  requestNo;
    private Long    userId;
    private Long    activityId;
    private Long    skuId;
    private Integer quantity;
    /** {@link com.fss.common.enums.SeckillRequestStatus} */
    private Integer status;
    private String  orderNo;
    private String  failReason;
    private String  traceId;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

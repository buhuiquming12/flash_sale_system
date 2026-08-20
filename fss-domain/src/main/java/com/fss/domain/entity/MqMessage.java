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
 * 本地消息表（决策 5）。
 *
 * <p>先落盘再发送，覆盖"Redis 已扣库存但进程在发消息前崩溃"这个窗口——
 * 内存里的重试意图会随进程消失，落盘的不会。代价是一次非热点单行 INSERT。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_mq_message")
public class MqMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String        msgId;
    private String        topic;
    private String        tags;
    /** RocketMQ key，通常 = request_no 或 order_no */
    private String        bizKey;
    private String        body;
    /** 定时投递时刻，null = 即时投递 */
    private LocalDateTime deliverTime;
    /** {@link com.fss.common.enums.MqStatus} */
    private Integer       status;
    private Integer       sendCount;
    private LocalDateTime nextRetryAt;
    private String        lastError;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

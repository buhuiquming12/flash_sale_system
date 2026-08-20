package com.fss.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fss.domain.entity.MqMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 本地消息表 Mapper。阶段三接入 RocketMQ 后启用，阶段一只建表不写入。 */
@Mapper
public interface MqMessageMapper extends BaseMapper<MqMessage> {

    @Select("SELECT * FROM t_mq_message WHERE msg_id = #{msgId}")
    MqMessage selectByMsgId(@Param("msgId") String msgId);

    @Select("""
            SELECT * FROM t_mq_message
             WHERE biz_key = #{bizKey}
             ORDER BY id DESC LIMIT 1
            """)
    MqMessage selectByBizKey(@Param("bizKey") String bizKey);

    /** 重发任务：走 idx_status_retry */
    @Select("""
            SELECT * FROM t_mq_message
             WHERE status = 0 AND next_retry_at <= #{now}
             ORDER BY id
             LIMIT #{limit}
            """)
    List<MqMessage> selectPendingForRetry(@Param("now") LocalDateTime now,
                                          @Param("limit") int limit);

    @Update("UPDATE t_mq_message SET status = 1 WHERE msg_id = #{msgId} AND status = 0")
    int markSent(@Param("msgId") String msgId);

    @Update("UPDATE t_mq_message SET status = 2 WHERE msg_id = #{msgId}")
    int markConsumed(@Param("msgId") String msgId);

    @Update("""
            UPDATE t_mq_message
               SET status = 3, last_error = #{error}
             WHERE msg_id = #{msgId}
            """)
    int markFailed(@Param("msgId") String msgId, @Param("error") String error);

    /** 指数退避：next_retry_at = now + backoffSeconds */
    @Update("""
            UPDATE t_mq_message
               SET send_count = send_count + 1,
                   next_retry_at = DATE_ADD(NOW(3), INTERVAL #{backoffSeconds} SECOND),
                   last_error = #{error}
             WHERE msg_id = #{msgId}
            """)
    int markRetry(@Param("msgId") String msgId,
                  @Param("backoffSeconds") long backoffSeconds,
                  @Param("error") String error);
}

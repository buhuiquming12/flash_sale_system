package com.fss.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fss.domain.entity.MqMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 本地消息表 Mapper。 */
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

    /**
     * 按业务键 + Topic 查。
     *
     * <p>同一个 {@code requestNo} 会在多个 Topic 下各有一条消息
     * （ORDER_CREATE 与后续可能的 STOCK_ROLLBACK），只按 bizKey 查会拿错那条。
     * {@link #selectByBizKey} 的 {@code ORDER BY id DESC LIMIT 1} 掩盖了这一点——
     * 它返回"最后登记的那条"，而调用方想要的通常是特定 Topic 的那条。
     */
    @Select("""
            SELECT * FROM t_mq_message
             WHERE biz_key = #{bizKey} AND topic = #{topic}
             ORDER BY id DESC LIMIT 1
            """)
    MqMessage selectByBizKeyAndTopic(@Param("bizKey") String bizKey,
                                     @Param("topic") String topic);

    /**
     * 重发任务：捞待发送且已到重试时刻的记录。
     *
     * <p>{@code send_count < #{maxSendCount}} 必须写在 SQL 里而不是捞出来再判断：
     * 不写的话，超过次数的记录每 30 秒都会被捞出来一次、判断一次、跳过一次，
     * 而它们永远不会消失（状态仍是 0），最终把每一轮的 200 条配额全占满，
     * 真正需要重发的新消息一条也捞不到。这类"队头阻塞"在量小的时候完全看不出来。
     */
    @Select("""
            SELECT * FROM t_mq_message
             WHERE status = 0 AND next_retry_at <= #{now} AND send_count < #{maxSendCount}
             ORDER BY id
             LIMIT #{limit}
            """)
    List<MqMessage> selectPendingForRetry(@Param("now") LocalDateTime now,
                                          @Param("maxSendCount") int maxSendCount,
                                          @Param("limit") int limit);

    /** 重试次数已耗尽、仍未发出的记录。需要走补偿回补 */
    @Select("""
            SELECT * FROM t_mq_message
             WHERE status = 0 AND send_count >= #{maxSendCount}
             ORDER BY id
             LIMIT #{limit}
            """)
    List<MqMessage> selectExhausted(@Param("maxSendCount") int maxSendCount,
                                    @Param("limit") int limit);

    @Update("UPDATE t_mq_message SET status = 1 WHERE msg_id = #{msgId} AND status = 0")
    int markSent(@Param("msgId") String msgId);

    @Update("UPDATE t_mq_message SET status = 2 WHERE msg_id = #{msgId}")
    int markConsumed(@Param("msgId") String msgId);

    /**
     * 消费端只拿得到 RocketMQ 的 KEYS（= bizKey），拿不到我们的 msg_id。
     *
     * <p>只从"已发送(1)"推进到"已消费(2)"：待发送(0) 的记录被标成已消费，
     * 重发任务就再也不会碰它了——而它可能是同一 bizKey 的另一条尚未发出的消息。
     */
    @Update("""
            UPDATE t_mq_message SET status = 2
             WHERE biz_key = #{bizKey} AND topic = #{topic} AND status = 1
            """)
    int markConsumedByBizKey(@Param("bizKey") String bizKey, @Param("topic") String topic);

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

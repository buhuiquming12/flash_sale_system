package com.fss.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fss.domain.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    /** 预热任务用：待开始且即将开始的活动 */
    @Select("""
            SELECT * FROM t_seckill_activity
             WHERE status = 1 AND start_time <= #{deadline}
             ORDER BY start_time
            """)
    List<SeckillActivity> selectReadyBefore(@Param("deadline") LocalDateTime deadline);

    /**
     * 预热任务真正用的查询：待开始、即将开始、<b>且尚未预热成功</b>的活动。
     *
     * <p>不加 {@code warmup_state} 条件的话，任务每分钟都会把同一批活动重新预热一遍。
     * 预热本身是幂等的所以不会错，但每次都会打一行"库存已存在，跳过初始化"的 warn 日志——
     * 真正的重复预热（人为误操作）就此淹没在噪音里，这个日志也就白打了。
     *
     * <p>{@code warmup_state IN (0, 3)} 覆盖未预热与预热失败：失败的会被自动重试，
     * 不需要人工重新触发。
     */
    @Select("""
            SELECT * FROM t_seckill_activity
             WHERE status = 1
               AND warmup_state IN (0, 3)
               AND start_time <= #{deadline}
               AND end_time > #{now}
             ORDER BY start_time
            """)
    List<SeckillActivity> selectNeedWarmup(@Param("deadline") LocalDateTime deadline,
                                           @Param("now") LocalDateTime now);

    /**
     * READY → RUNNING。
     *
     * <p>{@code warmup_state = 2} 这个条件是关键：<b>预热未完成的活动不会进入
     * RUNNING</b>，这防止了"到点开抢但 Redis 没数据"导致所有请求返回未预热。
     * 此时用户看到的是"活动即将开始"，比看到系统错误好。
     *
     * <p>阶段一（纯 MySQL 同步链路）无预热概念，由 {@code warmup_state} 在发布时
     * 直接置为 2 满足该条件。
     */
    @Update("""
            UPDATE t_seckill_activity
               SET status = 2
             WHERE status = 1
               AND warmup_state = 2
               AND start_time <= #{now}
            """)
    int startReadyActivities(@Param("now") LocalDateTime now);

    @Select("""
            SELECT id FROM t_seckill_activity
             WHERE status = 2 AND end_time <= #{now}
            """)
    List<Long> selectRunningEnded(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_seckill_activity
               SET status = 3
             WHERE id = #{id} AND status = 2
            """)
    int endActivity(@Param("id") long id);

    /** 状态条件更新。影响行数 0 → 别人抢先改了，调用方须重新决策 */
    @Update("""
            UPDATE t_seckill_activity
               SET status = #{to}
             WHERE id = #{id} AND status = #{from}
            """)
    int updateStatus(@Param("id") long id, @Param("from") int from, @Param("to") int to);

    @Update("""
            UPDATE t_seckill_activity
               SET warmup_state = #{state}
             WHERE id = #{id}
            """)
    int updateWarmupState(@Param("id") long id, @Param("state") int state);

    @Update("""
            UPDATE t_seckill_activity
               SET warmup_state = 2,
                   warmup_version = warmup_version + 1,
                   warmup_time = NOW(3)
             WHERE id = #{id}
            """)
    int markWarmupDone(@Param("id") long id);

    /**
     * 同一 SKU 的活动时间段冲突检测。
     *
     * <p>区间重叠的标准判定是 {@code a.start < b.end AND a.end > b.start}。
     * 写成 {@code a.start BETWEEN b.start AND b.end} 会漏掉 b 完全包含在 a 内的情况。
     */
    @Select("""
            SELECT COUNT(1) FROM t_seckill_activity a
              JOIN t_seckill_goods g ON g.activity_id = a.id
             WHERE g.sku_id = #{skuId}
               AND a.id <> #{excludeId}
               AND a.status IN (1, 2)
               AND a.start_time < #{endTime}
               AND a.end_time   > #{startTime}
            """)
    int countOverlapping(@Param("skuId") long skuId,
                         @Param("startTime") LocalDateTime startTime,
                         @Param("endTime") LocalDateTime endTime,
                         @Param("excludeId") long excludeId);

    /** 对账用：进行中或近期结束的活动 */
    @Select("""
            SELECT id FROM t_seckill_activity
             WHERE status = 2
                OR (status = 3 AND end_time > #{since})
            """)
    List<Long> selectRunningOrRecentEnded(@Param("since") LocalDateTime since);
}

package com.fss.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fss.domain.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    @Select("SELECT * FROM t_order WHERE order_no = #{orderNo}")
    Order selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 按订单号 + 用户查询。
     *
     * <p><b>越权校验必须这样写</b>，不能先 {@code selectByOrderNo} 再比对 userId：
     * 后者对"订单不存在"和"订单不属于你"返回完全一致的结果与耗时，
     * 不泄漏订单号是否有效。
     */
    @Select("SELECT * FROM t_order WHERE order_no = #{orderNo} AND user_id = #{userId}")
    Order selectByOrderNoAndUser(@Param("orderNo") String orderNo,
                                 @Param("userId") long userId);

    /** 幂等层 1：请求号已处理？命中直接 ACK，不做任何写操作 */
    @Select("SELECT * FROM t_order WHERE request_no = #{requestNo}")
    Order selectByRequestNo(@Param("requestNo") String requestNo);

    /**
     * 幂等层 2：该用户在该活动该 SKU 上已有订单？
     *
     * <p>命中说明是不同 requestNo 的重复请求（Redis 资格判定失效后的兜底），
     * 属于确定性失败，需要补偿回补而不是简单 ACK——后者会让库存永久泄漏。
     * 注意查询不带 status 条件：<b>已取消的订单同样占用唯一键</b>（决策 1，取消后不重抢）。
     */
    @Select("""
            SELECT * FROM t_order
             WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND user_id = #{userId}
            """)
    Order selectByActivitySkuUser(@Param("activityId") long activityId,
                                  @Param("skuId") long skuId,
                                  @Param("userId") long userId);

    /**
     * 状态条件更新 —— 并发正确性的真正来源。
     *
     * <p>状态机断言只是防御性编程；支付与关单同时到达时，靠的是两者都
     * {@code WHERE status = #{from}} 竞争同一行，MySQL 行锁保证只有一个
     * {@code rows == 1}。影响行数 0 → 别人抢先了，读最新状态重新决策。
     */
    @Update("""
            UPDATE t_order
               SET status = #{to}
             WHERE order_no = #{orderNo} AND status = #{from}
            """)
    int updateStatus(@Param("orderNo") String orderNo,
                     @Param("from") int from,
                     @Param("to") int to);

    @Update("""
            UPDATE t_order
               SET status = #{to}, pay_time = NOW(3)
             WHERE order_no = #{orderNo} AND status = #{from}
            """)
    int markPaid(@Param("orderNo") String orderNo,
                 @Param("from") int from,
                 @Param("to") int to);

    @Update("""
            UPDATE t_order
               SET status = 2, cancel_time = NOW(3), cancel_reason = #{reason}
             WHERE order_no = #{orderNo} AND status = 0
            """)
    int markCancelled(@Param("orderNo") String orderNo, @Param("reason") String reason);

    /** 取消回补的第一层幂等：条件更新把自己变成唯一执行者 */
    @Update("""
            UPDATE t_order
               SET stock_released = 1
             WHERE order_no = #{orderNo} AND stock_released = 0
            """)
    int markStockReleased(@Param("orderNo") String orderNo);

    /**
     * 超时扫描，走 {@code idx_status_expire}。
     *
     * <p>游标分页（{@code id > lastId}）而非 OFFSET：OFFSET 在深翻页时要扫过并
     * 丢弃前面所有行，且并发关单会让行位移导致漏扫。
     */
    @Select("""
            SELECT * FROM t_order
             WHERE status = 0 AND expire_time < #{deadline} AND id > #{lastId}
             ORDER BY id
             LIMIT #{limit}
            """)
    List<Order> selectExpiredPendingPay(@Param("deadline") LocalDateTime deadline,
                                        @Param("lastId") long lastId,
                                        @Param("limit") int limit);

    @Select("SELECT COUNT(1) FROM t_order WHERE activity_id = #{activityId}")
    long countByActivity(@Param("activityId") long activityId);

    @Select("""
            SELECT COUNT(1) FROM t_order
             WHERE activity_id = #{activityId} AND sku_id = #{skuId}
            """)
    long countByActivitySku(@Param("activityId") long activityId, @Param("skuId") long skuId);

    /** 库存对账用：除已取消外全部状态的占用量 */
    @Select("""
            SELECT COALESCE(SUM(quantity), 0) FROM t_order
             WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND status <> 2
            """)
    int sumEffectiveQuantity(@Param("activityId") long activityId,
                             @Param("skuId") long skuId);

    @Select("""
            SELECT * FROM t_order
             WHERE user_id = #{userId}
               AND (#{status} IS NULL OR status = #{status})
             ORDER BY create_time DESC, id DESC
             LIMIT #{offset}, #{limit}
            """)
    List<Order> selectUserOrders(@Param("userId") long userId,
                                 @Param("status") Integer status,
                                 @Param("offset") long offset,
                                 @Param("limit") long limit);

    @Select("""
            SELECT COUNT(1) FROM t_order
             WHERE user_id = #{userId} AND (#{status} IS NULL OR status = #{status})
            """)
    long countUserOrders(@Param("userId") long userId, @Param("status") Integer status);
}

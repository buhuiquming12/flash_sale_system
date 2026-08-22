package com.fss.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fss.domain.dto.PaymentDiff;
import com.fss.domain.entity.Payment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PaymentMapper extends BaseMapper<Payment> {

    @Select("SELECT * FROM t_payment WHERE pay_no = #{payNo}")
    Payment selectByPayNo(@Param("payNo") String payNo);

    /** 复用已有待支付流水，避免同一订单产生多条流水 */
    @Select("""
            SELECT * FROM t_payment
             WHERE order_no = #{orderNo} AND status = 0
             ORDER BY id DESC LIMIT 1
            """)
    Payment selectPendingByOrderNo(@Param("orderNo") String orderNo);

    @Select("""
            SELECT * FROM t_payment
             WHERE order_no = #{orderNo} AND status = 1
             ORDER BY id DESC LIMIT 1
            """)
    Payment selectSuccessByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 条件更新到支付成功。
     *
     * <p>{@code AND status = 0} 保证重复回调只有一次能改，
     * {@code out_trade_no} 同时落库，由 {@code uk_out_trade_no} 兜底幂等。
     */
    @Update("""
            UPDATE t_payment
               SET status = 1, out_trade_no = #{outTradeNo},
                   notify_body = #{notifyBody}, finish_time = NOW(3)
             WHERE pay_no = #{payNo} AND status = 0
            """)
    int markSuccess(@Param("payNo") String payNo,
                    @Param("outTradeNo") String outTradeNo,
                    @Param("notifyBody") String notifyBody);

    @Update("""
            UPDATE t_payment
               SET status = #{to}, finish_time = NOW(3)
             WHERE pay_no = #{payNo} AND status = #{from}
            """)
    int updateStatus(@Param("payNo") String payNo,
                     @Param("from") int from,
                     @Param("to") int to);

    // ==================================================================
    // 支付对账（docs/05 §9.3）。四类差异，每类一条查询。
    //
    // 全部带 finish_time / create_time 的时间下限：支付回调与订单状态推进之间
    // 天然有几十毫秒到几秒的窗口，不带时间条件会把<b>正在处理中</b>的支付
    // 全都当成差异——每 10 分钟刷一批假差异，真差异反而被淹没。
    // ==================================================================

    /**
     * A 类：支付成功但订单仍是<b>待支付</b>。
     *
     * <p><b>必须限定 {@code o.status = 0}</b>。设计文档写的是
     * {@code o.status NOT IN (1,3,4,5)}，那个条件把 {@code status = 2}（已取消）
     * 也捞进来了——而"支付成功 + 订单已取消"是 D 类，处理方式完全相反：
     * A 类可以自动补推订单状态，D 类<b>绝不能</b>（库存已经还给别人了，
     * 补推等于超卖），只能退款并转人工。两类混在一条查询里，
     * 自动修复逻辑就会把资损事件"修"成超卖事件。
     */
    @Select("""
            SELECT p.pay_no AS payNo, p.order_no AS orderNo, p.user_id AS userId,
                   p.amount AS amount, p.status AS payStatus, p.finish_time AS finishTime,
                   o.pay_amount AS orderAmount, o.status AS orderStatus,
                   o.activity_id AS activityId, o.sku_id AS skuId, o.quantity AS quantity
              FROM t_payment p
              JOIN t_order o ON o.order_no = p.order_no
             WHERE p.status = 1
               AND o.status = 0
               AND p.finish_time < #{before}
             LIMIT #{limit}
            """)
    List<PaymentDiff> selectPaidButOrderPending(@Param("before") LocalDateTime before,
                                                @Param("limit") int limit);

    /**
     * B 类：订单已支付但没有成功流水。
     *
     * <p>一律人工：可能是流水被误改，也可能是订单被误推进。两种成因的修复方向相反，
     * 自动猜错一次就是资金账目错乱。
     */
    @Select("""
            SELECT o.order_no AS orderNo, o.user_id AS userId,
                   o.pay_amount AS orderAmount, o.status AS orderStatus,
                   o.activity_id AS activityId, o.sku_id AS skuId, o.quantity AS quantity
              FROM t_order o
             WHERE o.status = 1
               AND o.pay_time < #{before}
               AND NOT EXISTS (SELECT 1 FROM t_payment p
                                WHERE p.order_no = o.order_no AND p.status = 1)
             LIMIT #{limit}
            """)
    List<PaymentDiff> selectPaidWithoutSuccessPayment(@Param("before") LocalDateTime before,
                                                      @Param("limit") int limit);

    /** C 类：金额不一致。直接 P1——它意味着验金额那一层被绕过了 */
    @Select("""
            SELECT p.pay_no AS payNo, p.order_no AS orderNo, p.user_id AS userId,
                   p.amount AS amount, p.status AS payStatus, p.finish_time AS finishTime,
                   o.pay_amount AS orderAmount, o.status AS orderStatus,
                   o.activity_id AS activityId, o.sku_id AS skuId, o.quantity AS quantity
              FROM t_payment p
              JOIN t_order o ON o.order_no = p.order_no
             WHERE p.status = 1
               AND p.amount <> o.pay_amount
             LIMIT #{limit}
            """)
    List<PaymentDiff> selectAmountMismatch(@Param("limit") int limit);

    /**
     * D 类：已取消订单出现支付成功。
     *
     * <p>{@code p.status = 1} 而不是 {@code IN (1, 3)}：已退款(3)的正是支付与关单
     * 竞态<b>已经处理过</b>的那些，把它们也捞出来会让每一轮对账都重复报同一批差异，
     * 而实际上退款流程已经走完并落过对账任务了。
     */
    @Select("""
            SELECT p.pay_no AS payNo, p.order_no AS orderNo, p.user_id AS userId,
                   p.amount AS amount, p.status AS payStatus, p.finish_time AS finishTime,
                   o.pay_amount AS orderAmount, o.status AS orderStatus,
                   o.activity_id AS activityId, o.sku_id AS skuId, o.quantity AS quantity
              FROM t_payment p
              JOIN t_order o ON o.order_no = p.order_no
             WHERE p.status = 1
               AND o.status = 2
             LIMIT #{limit}
            """)
    List<PaymentDiff> selectPaidButOrderCancelled(@Param("limit") int limit);
}

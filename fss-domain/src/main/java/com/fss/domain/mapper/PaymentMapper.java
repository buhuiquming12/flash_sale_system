package com.fss.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fss.domain.entity.Payment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
}

package com.fss.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fss.domain.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    @Select("SELECT * FROM t_order_item WHERE order_no = #{orderNo} ORDER BY id")
    List<OrderItem> selectByOrderNo(@Param("orderNo") String orderNo);

    @Select("""
            <script>
            SELECT * FROM t_order_item
             WHERE order_no IN
             <foreach collection="orderNos" item="no" open="(" separator="," close=")">#{no}</foreach>
            </script>
            """)
    List<OrderItem> selectByOrderNos(@Param("orderNos") List<String> orderNos);
}

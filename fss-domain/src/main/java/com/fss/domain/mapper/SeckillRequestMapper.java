package com.fss.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fss.domain.entity.SeckillRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeckillRequestMapper extends BaseMapper<SeckillRequest> {

    @Select("SELECT * FROM t_seckill_request WHERE request_no = #{requestNo}")
    SeckillRequest selectByRequestNo(@Param("requestNo") String requestNo);

    /** 只允许从"排队中"推进，不覆盖终态 */
    @Update("""
            UPDATE t_seckill_request
               SET status = #{status}, order_no = #{orderNo}, fail_reason = #{failReason}
             WHERE request_no = #{requestNo} AND status = 0
            """)
    int advanceStatus(@Param("requestNo") String requestNo,
                      @Param("status") int status,
                      @Param("orderNo") String orderNo,
                      @Param("failReason") String failReason);
}

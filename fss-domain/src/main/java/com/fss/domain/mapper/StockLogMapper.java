package com.fss.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fss.domain.entity.StockLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface StockLogMapper extends BaseMapper<StockLog> {

    @Select("""
            SELECT COUNT(1) FROM t_stock_log
             WHERE biz_no = #{bizNo} AND change_type = #{changeType}
            """)
    int countByBizAndType(@Param("bizNo") String bizNo, @Param("changeType") int changeType);
}

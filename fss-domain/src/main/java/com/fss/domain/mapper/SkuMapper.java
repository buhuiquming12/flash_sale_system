package com.fss.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fss.domain.entity.Sku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SkuMapper extends BaseMapper<Sku> {

    @Select("SELECT * FROM t_sku WHERE product_id = #{productId} ORDER BY id")
    List<Sku> selectByProduct(@Param("productId") long productId);
}

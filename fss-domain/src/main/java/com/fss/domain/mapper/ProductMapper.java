package com.fss.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fss.domain.entity.Product;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}

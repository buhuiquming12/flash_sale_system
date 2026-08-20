package com.fss.infra.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/** MyBatis-Plus 配置。 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 自动填充时间字段。
     *
     * <p>只在实体字段为空时填充，让 DDL 里的 {@code DEFAULT CURRENT_TIMESTAMP(3)}
     * 与 Java 侧不打架。业务时间判定（活动窗口）绝不用这里的
     * {@code LocalDateTime.now()}——多实例时钟漂移，见 F14。
     */
    @Bean
    @ConditionalOnMissingBean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                fillIfNull(metaObject, "createTime", now);
                fillIfNull(metaObject, "updateTime", now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                fillIfNull(metaObject, "updateTime", LocalDateTime.now());
            }

            private void fillIfNull(MetaObject metaObject, String field, LocalDateTime value) {
                if (metaObject.hasSetter(field) && getFieldValByName(field, metaObject) == null) {
                    setFieldValByName(field, value, metaObject);
                }
            }
        };
    }
}

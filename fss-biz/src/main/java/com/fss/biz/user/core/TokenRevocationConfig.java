package com.fss.biz.user.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Token 撤销存储的装配。
 *
 * <p><b>一个 {@code @Bean} 方法、一次显式判断，不用条件注解。</b>
 * 曾经的写法是 Noop 实现挂 {@code @ConditionalOnMissingBean}、Redis 实现挂
 * {@code @Component}，让前者自动让位。它能工作，但正确性依赖一个很细的前提：
 * 组件扫描注册的 Bean 定义必须早于本配置类的 {@code @Bean} 方法被求值。
 * 这个前提在当前 Spring 版本下成立，却是<b>实现细节</b>而不是契约——
 * 而它一旦不成立，症状是启动时报"两个实现都没有"或者悄悄用错了实现。
 *
 * <p>阶段一那次踩的坑（{@code @ConditionalOnMissingBean} 写在被扫描的
 * {@code @Component} 上完全不可靠）说明这类"自动让位"的机制不值得用在
 * 只有两个候选的地方。写死判断反而更短、更明确、更好测。
 */
@Slf4j
@Configuration
public class TokenRevocationConfig {

    /**
     * @param redis 用 {@link ObjectProvider} 而不是直接注入：没接 Redis 的部署形态
     *              （或 Redis 自动装配被排除时）也要能启动，退化成 Noop 实现
     */
    @Bean
    public TokenRevocationStore tokenRevocationStore(ObjectProvider<StringRedisTemplate> redis) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            log.warn("未检测到 Redis，JWT 主动失效降级为 Noop：登出只是客户端丢弃 token，"
                    + "服务端仍认这个 token 直到过期");
            return new NoopTokenRevocationStore();
        }
        return new RedisTokenRevocationStore(template);
    }
}

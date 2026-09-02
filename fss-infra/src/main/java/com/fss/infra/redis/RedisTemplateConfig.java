package com.fss.infra.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 接管 {@link StringRedisTemplate} 的装配，只为换掉脚本执行器。
 *
 * <p>Boot 的 {@code RedisAutoConfiguration#stringRedisTemplate} 带
 * {@code @ConditionalOnMissingBean}，这里定义了同类型的 Bean 它就会退让。
 * 除执行器之外一切与自动装配保持一致（连接工厂、四个 String 序列化器都由
 * {@code StringRedisTemplate} 自己的构造函数装好），不引入别的差异 ——
 * 覆盖自动装配的 Bean 最容易出的问题是顺手改了序列化器，
 * 那会让已经写进 Redis 的数据读不出来。
 *
 * <p>本项目全部 Lua 调用都走 {@code StringRedisTemplate}
 * （{@code SeckillExecutor} 的四个脚本 + {@code RateLimiter} 的令牌桶），
 * 所以换这一个 Bean 就覆盖了所有脚本路径。
 */
@Configuration
public class RedisTemplateConfig {

    /**
     * @see EvalShaScriptExecutor 为什么不能用默认执行器的 EVAL 回落
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        // 构造函数内部已经 afterPropertiesSet() 过一次、装了默认执行器，这里替换掉。
        // 容器随后再调一次 afterPropertiesSet() 时会看到 scriptExecutor 非空，不会覆盖回去
        template.setScriptExecutor(new EvalShaScriptExecutor<>(template));
        return template;
    }
}

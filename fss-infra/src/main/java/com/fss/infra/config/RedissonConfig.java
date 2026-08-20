package com.fss.infra.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 装配 —— 只为分布式锁而存在。
 *
 * <p><b>为什么与 Lettuce 并存而不用 redisson-spring-boot-starter</b>：
 * 那个 starter 会顶掉 Spring Boot 自动装配的 {@code LettuceConnectionFactory}，
 * 于是 {@code StringRedisTemplate} 和全部 Lua 调用都改走 Redisson。
 * 本项目的取舍是分工而不是统一：Lua 与普通读写用 Lettuce（Spring Data Redis 的
 * 一等公民，脚本缓存、序列化都是现成的），锁用 Redisson（看门狗续期、
 * 可重入、公平锁这些自己写容易写错）。代价是两个连接池，
 * 换来的是任一侧升级都不会连带影响另一侧。
 *
 * <p><b>为什么手写锁不划算</b>：{@code SET NX PX} + 比对 value 再删除的写法看着简单，
 * 但漏掉的是「业务执行时间超过 leaseTime 怎么办」。锁提前过期后另一个实例拿到锁，
 * 两个实例同时跑同一个任务。Redisson 的看门狗每 {@code lockWatchdogTimeout/3}
 * 续一次期，从机制上避免这个问题——而这段续期逻辑自己实现要处理线程池、
 * 中断、客户端断连，不是二十行能写对的。
 */
@Slf4j
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties props) {
        Config config = new Config();
        // 锁只操作整型与短字符串，Redisson 的 RLock 内部固定用 LongCodec，
        // 这里显式设 StringCodec 是为了避免默认的 Kryo5Codec 在被顺手用到时
        // 引入额外的序列化依赖与不可读的 value
        config.setCodec(StringCodec.INSTANCE);

        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + props.getHost() + ":" + props.getPort())
                .setDatabase(props.getDatabase())
                // 锁的调用极短（一次 EVAL），池子不需要大；
                // 但也不能太小，否则 job 与缓存重建会互相等连接
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(16)
                .setConnectTimeout(2000)
                .setTimeout(2000)
                // tryLock(0, ...) 立刻返回，重试没有意义
                .setRetryAttempts(1);
        if (StringUtils.hasText(props.getPassword())) {
            server.setPassword(props.getPassword());
        }

        log.info("Redisson 初始化 address={}:{} db={}",
                props.getHost(), props.getPort(), props.getDatabase());
        return Redisson.create(config);
    }
}

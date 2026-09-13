package com.fss.infra.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.List;

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

        // 单机与哨兵的唯一差别是"地址从哪来"。池子大小、超时、库号这些必须
        // 两形态一致——否则会出现"压测在单机上调好的参数，上哨兵后行为不同"，
        // 而那种差异只会在故障切换的那一刻暴露
        if (useSentinel(props)) {
            configureSentinel(config, props);
        } else {
            configureSingle(config, props);
        }
        return Redisson.create(config);
    }

    /**
     * 是否走哨兵形态。
     *
     * <p>两个条件都要判：
     * <ul>
     *   <li><b>判 null</b>：{@code getSentinel()} 在未配置时返回 null 还是空对象，
     *       各 Spring Boot 版本语义并不一致。null-safe 的写法对两种都成立，
     *       不必去赌当前版本是哪种。</li>
     *   <li><b>判节点列表为空</b>：只判非 null 的话，{@code sentinel.nodes} 为空时
     *       会建出一个"零个哨兵地址"的连接池，报错发生在 Redisson 内部而不是
     *       配置校验处——排查成本远高于在这里安静地退回单机。</li>
     * </ul>
     */
    private boolean useSentinel(RedisProperties props) {
        RedisProperties.Sentinel sentinel = props.getSentinel();
        return sentinel != null
                && sentinel.getNodes() != null
                && !sentinel.getNodes().isEmpty();
    }

    /**
     * 哨兵形态。只影响分布式锁这一侧——Lua 与普通读写走 Lettuce 的
     * {@code StringRedisTemplate}，它的哨兵装配由 Spring Boot 依据
     * {@code spring.data.redis.sentinel.*} 独立完成。
     *
     * <p>这也是为什么 master 名必须和 {@code sentinel.conf} 里 monitor 的
     * master 名一致：两侧各自建连、各自去哨兵问 master，名字对不上时
     * 报错发生在建连阶段且指向 Redisson，看不出是配置名不一致。
     */
    private void configureSentinel(Config config, RedisProperties props) {
        RedisProperties.Sentinel sentinel = props.getSentinel();
        List<String> nodes = sentinel.getNodes();

        SentinelServersConfig server = config.useSentinelServers()
                .setMasterName(sentinel.getMaster())
                .addSentinelAddress(nodes.stream()
                        .map(node -> "redis://" + node)
                        .toArray(String[]::new));
        applyCommon(server, props);

        log.info("Redisson 初始化(哨兵) masterName={} sentinels={} db={}",
                sentinel.getMaster(), nodes, props.getDatabase());
    }

    private void configureSingle(Config config, RedisProperties props) {
        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + props.getHost() + ":" + props.getPort());
        applyCommon(server, props);

        log.info("Redisson 初始化(单机) address={}:{} db={}",
                props.getHost(), props.getPort(), props.getDatabase());
    }

    /**
     * 哨兵形态的共用参数。
     *
     * <p><b>池子参数与单机形态不是同一组方法</b>，这不是重复代码，而是两种配置
     * 形状本来就不同：Redisson 的 master-slave 系配置（哨兵属于这一系）没有单一的
     * {@code connectionPoolSize}，而是 master / slave 各一对——读可能落到从库上，
     * 池子必须分开算。单机形态那组 setter 在这里<b>根本不存在</b>。
     *
     * <p>两侧给一样的值：锁的读写都落在 master 上，但 Redisson 也会为从库建连接，
     * 只给 master 配、让 slave 落回默认值，等于悄悄改了另一个参数。
     *
     * <p>要与单机分支逐项对齐的只有库号、超时、重试这三组。改一处就要改另一处，
     * 否则会出现"压测在单机上调好的参数，上哨兵后行为不同"，
     * 而那种差异只会在故障切换的那一刻暴露。
     */
    private void applyCommon(SentinelServersConfig server, RedisProperties props) {
        server.setDatabase(props.getDatabase())
                // 锁的调用极短（一次 EVAL），池子不需要大；
                // 但也不能太小，否则 job 与缓存重建会互相等连接
                .setMasterConnectionMinimumIdleSize(2)
                .setSlaveConnectionMinimumIdleSize(2)
                .setMasterConnectionPoolSize(16)
                .setSlaveConnectionPoolSize(16)
                .setConnectTimeout(2000)
                .setTimeout(2000)
                // tryLock(0, ...) 立刻返回，重试没有意义
                .setRetryAttempts(1);
        if (StringUtils.hasText(props.getPassword())) {
            server.setPassword(props.getPassword());
        }
    }

    /** 单机形态的共用参数，与哨兵分支逐项对齐（见上面那个重载的注释）。 */
    private void applyCommon(SingleServerConfig server, RedisProperties props) {
        server.setDatabase(props.getDatabase())
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(16)
                .setConnectTimeout(2000)
                .setTimeout(2000)
                .setRetryAttempts(1);
        if (StringUtils.hasText(props.getPassword())) {
            server.setPassword(props.getPassword());
        }
    }
}

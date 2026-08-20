package com.fss.infra.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

/**
 * Lua 脚本装配。
 *
 * <p>{@link DefaultRedisScript} 会缓存脚本的 SHA1，运行时走 {@code EVALSHA}，
 * 脚本正文只在首次（或 Redis 重启后 {@code NOSCRIPT} 时）上传一次。
 * 直接用 {@code EVAL} 的话每个请求都要把上百行脚本正文推过网络，
 * 10000 QPS 下这是几十 MB/s 的无谓带宽。
 *
 * <p>脚本放在 {@code resources/lua/} 而不是写成 Java 字符串常量：
 * 独立文件能被编辑器按 Lua 语法高亮和检查，字符串常量里的语法错误
 * 只有到运行时才会被 Redis 报出来。
 *
 * <p><b>返回类型的选择</b>：脚本 A 返回 {@code {code, remain}} 两元素表，
 * 对应 {@code List}；其余脚本返回单个整数，对应 {@code Long}。
 * 类型写错时 Spring 会在反序列化阶段抛
 * {@code ClassCastException}，而不是给出静默的错误值。
 */
@Configuration
public class LuaScriptConfig {

    /** 脚本 A：资格判定与预扣，返回 {code, remainStock} */
    @Bean("seckillScript")
    @SuppressWarnings("rawtypes")
    public RedisScript<List> seckillScript() {
        return load("lua/seckill.lua", List.class);
    }

    /** 脚本 B：补偿回补（ROLLBACK），返回 0 已回补 / 1 幂等命中 */
    @Bean("rollbackScript")
    public RedisScript<Long> rollbackScript() {
        return load("lua/rollback.lua", Long.class);
    }

    /** 脚本 C：取消回补（RELEASE），返回 0 已回补 / 1 幂等命中 */
    @Bean("releaseScript")
    public RedisScript<Long> releaseScript() {
        return load("lua/release.lua", Long.class);
    }

    /** 脚本 D：结果写入，返回 0 写入 / 1 已是终态 */
    @Bean("writeResultScript")
    public RedisScript<Long> writeResultScript() {
        return load("lua/write_result.lua", Long.class);
    }

    /** 脚本 E：令牌桶，返回 1 放行 / 0 拒绝 */
    @Bean("tokenBucketScript")
    public RedisScript<Long> tokenBucketScript() {
        return load("lua/token_bucket.lua", Long.class);
    }

    private <T> DefaultRedisScript<T> load(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(resultType);
        return script;
    }
}

package com.fss.infra.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 *
 * <h3>为什么用 setScriptText 而不是 ResourceScriptSource</h3>
 * {@code ResourceScriptSource} 每次 {@code getSha1()} 都要问一次
 * {@code isModified()}（对 ClassPathResource 是一次 {@code lastModified()} 系统调用），
 * 而脚本在打进 jar 之后不可能变。用 {@code setScriptText} 在启动时读一次、
 * 之后 SHA1 与正文都来自同一个不变的字符串，少一层不确定性。
 *
 * <h3>EVALSHA 曾经 100% 回落 EVAL（已修，成因不在本类）</h3>
 * 阶段五压测实测：一次秒杀请求发出 <b>2 次 EVALSHA（全部 NOSCRIPT）+ 4 次 EVAL</b>，
 * {@code errorstat_NOSCRIPT} 与 {@code cmdstat_evalsha.failed_calls} 同步增长，
 * 每次 Lua 调用都多一个 RTT 并重传整段正文。功能完全正确、无日志无告警，
 * 典型的静默性能退化。
 *
 * <p>成因是 Spring Data Redis 回落 {@code EVAL} 时把正文按<b>平台默认编码</b>
 * 解码再按 UTF-8 编回（{@code LettuceConverters.toString(byte[])} 是
 * {@code new String(bytes)}），GBK 下这个来回有损：Redis 隐式缓存到
 * {@code c9367cb8…}，而应用发的是 {@code 085cbf12…}。
 * 本类这一侧从头到尾都是对的——{@code getSha1()} 实测等于 sha1(UTF-8 正文)，
 * 也等于 Redis 对文件原字节算出的值，所以当初「编码不一致」这个假设被误判成排除了：
 * 错的不是 SHA1，是发正文的那条路。详见 {@link EvalShaScriptExecutor}，
 * 它换成用 {@code SCRIPT LOAD} 上传正文（不经 String 中转）。
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

    /**
     * 读脚本文件并显式 setScriptText。
     *
     * <p>不用 {@code setScriptSource(new ResourceScriptSource(...))} 的原因见类注释：
     * 那条路径每次 {@code getSha1()} 都要问一次 {@code isModified()}，
     * 而这里读进来的字符串此后不会再变。
     *
     * <p>启动时读一次、失败即启动失败。让它在启动时炸掉而不是运行时静默降级：
     * 少一个脚本意味着对应的那条链路完全不可用，
     * 而那种失败在第一个真实请求打进来时才暴露。
     */
    private <T> DefaultRedisScript<T> load(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setScriptText(read(path));
        script.setResultType(resultType);
        return script;
    }

    private static String read(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Lua 脚本读取失败: " + path, e);
        }
    }
}

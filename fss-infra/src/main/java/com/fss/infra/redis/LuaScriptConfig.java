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
 * <h3>已知问题：EVALSHA 目前 100% 回落 EVAL（未解决）</h3>
 * 阶段五压测实测：一次秒杀请求发出 <b>2 次 EVALSHA（全部 NOSCRIPT）+ 4 次 EVAL</b>，
 * Redis {@code errorstat_NOSCRIPT} 与 {@code cmdstat_evalsha.failed_calls} 同步增长。
 * 后果是每次 Lua 调用多一个 RTT 并重传整段脚本正文（本脚本 3.4KB）。
 *
 * <p><b>功能完全正确</b>——回落路径就是为此设计的，所有集成用例照常通过。
 * 它只影响性能，且不产生任何日志或告警，属于典型的静默退化。
 *
 * <p>已排除的四个假设（都有实测数据）：
 * <ol>
 *   <li><b>尾部换行差异</b>：Redis 对 jar 内文件原字节算出的 sha1hex
 *       与 {@code getSha1()} <b>完全相等</b>（都是 {@code 085cbf12...}）。
 *       早先看到的 {@code 66a826d3...} 是排查时
 *       {@code SCRIPT LOAD "$(cat file)"} 里 shell 的 {@code $()} 剥掉尾换行造成的，
 *       是排查动作的产物，不是应用行为。</li>
 *   <li><b>编码不一致</b>：平台默认编码是 GBK，但 {@code getSha1()} 实测等于
 *       sha1(UTF-8 正文)，GBK 变体的 sha1 是另一个值且不在缓存里。</li>
 *   <li><b>CRLF</b>：{@code core.autocrlf=true}，但 jar 内该文件是纯 LF。</li>
 *   <li><b>有人清了脚本缓存</b>：MONITOR 抓 10 秒空闲期，无
 *       {@code SCRIPT FLUSH / FLUSHALL / FLUSHDB}。</li>
 * </ol>
 * 而 {@code redis-cli --eval} 发同一份文件后 {@code SCRIPT EXISTS 085cbf12...}
 * 立刻变 1，说明 Redis 侧的 EVAL 隐式缓存与 SHA 计算都正常。
 * 所以问题出在 Spring Data Redis / Lettuce 发出 {@code EVAL} 时的正文与
 * {@code getSha1()} 所基于的正文之间——还没找到那个差异点。
 *
 * <p>{@link com.fss.metrics.LuaScriptShaTest} 已经把
 * 「{@code getSha1()} == sha1(getScriptAsString())」钉住，
 * 排除了本类这一侧的成因；剩下的要往 {@code DefaultScriptExecutor.scriptBytes()}
 * 与连接层的序列化器去查。
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
     * 那条路径下 SHA1 的计算与正文的上传用的不是同一个字符串，
     * {@code EVALSHA} 永远命中不了。
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

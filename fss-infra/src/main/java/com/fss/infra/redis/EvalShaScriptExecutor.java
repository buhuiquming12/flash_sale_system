package com.fss.infra.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultScriptExecutor;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脚本执行器：{@code EVALSHA} 未命中时用 {@code SCRIPT LOAD} 补缓存再重试，
 * 而不是回落到 {@code EVAL}。
 *
 * <h3>修的是什么</h3>
 * 阶段五压测实测到 EVALSHA 100% 回落 EVAL（{@code cmdstat_evalsha.failed_calls == calls}，
 * {@code errorstat_NOSCRIPT} 同步增长）。成因在 Spring Data Redis 的 Lettuce 适配层：
 * 回落路径把脚本正文<b>按平台默认编码解码一遍，再按 UTF-8 编回去</b>。
 * <pre>
 * DefaultScriptExecutor.scriptBytes()        body.getBytes(UTF_8)       正确的 UTF-8 字节
 * LettuceConverters.toString(byte[])         new String(bytes)          ← 平台默认编码解码
 * AbstractRedisAsyncCommands.encodeScript()  s.getBytes(scriptCharset)  ← 再按 UTF-8 编回
 * </pre>
 * 默认编码不是 UTF-8 时这个来回<b>有损</b>（本机 {@code file.encoding=GBK} + JDK 17；
 * 容器里 {@code LANG} 没设时是 US-ASCII，更糟）。于是：
 * <ul>
 *   <li>{@code getSha1()} = sha1(正文的 UTF-8 字节) = {@code 085cbf12…}，
 *       与 Redis 对文件原字节算出的值一致 —— 装配这一侧一直是对的；</li>
 *   <li>真正发出去的 EVAL 正文却是转码残渣（seckill.lua 从 5032 字节被撑到 6337 字节），
 *       Redis 的隐式缓存于是落在 {@code c9367cb8…}；</li>
 *   <li>下一次 {@code EVALSHA 085cbf12…} 照旧 NOSCRIPT —— 永远命中不了。</li>
 * </ul>
 * 功能没坏只是运气好：中文只出现在 {@code --} 注释里，Lua 不在乎注释是什么字节。
 * 哪天有人往字符串字面量里写一句中文提示，返回给调用方的就是乱码，
 * 那就不再是性能问题了。
 *
 * <h3>为什么不是给 JVM 加 -Dfile.encoding=UTF-8</h3>
 * 加启动参数也能修（默认编码是 UTF-8 时那个来回无损），但它把正确性<b>挂在一个
 * 能被悄悄丢掉的启动参数上</b>：IDE 里点运行、换一份 Dockerfile、换个基础镜像
 * 都可能丢掉它，而丢掉之后的症状正是原来那个不报错、不告警、只有压测才发现的静默退化。
 * {@code LettuceScriptingCommands.scriptLoad(byte[])} 把字节数组原样交给 Lettuce，
 * 中间没有 String 中转，所以走它与平台编码无关，怎么启动都对。
 *
 * <h3>顺带得到的两件事</h3>
 * <ol>
 *   <li><b>自校验</b>：{@code SCRIPT LOAD} 会返回 Redis 自己算的 sha1，与
 *       {@code getSha1()} 不等就说明传输层又在改字节了，直接打 ERROR。
 *       原来那个缺陷藏了整整一个阶段，就是因为它一条日志都不产生。</li>
 *   <li><b>能自愈</b>：Redis 重启或 {@code SCRIPT FLUSH} 之后，第一次调用补缓存并重试，
 *       之后继续走 EVALSHA，而不是从此每次都重传整段正文。</li>
 * </ol>
 *
 * @see LuaScriptConfig 脚本装配（SHA1 的来源）
 */
@Slf4j
public class EvalShaScriptExecutor<K> extends DefaultScriptExecutor<K> {

    /** 已经报过 sha1 不一致的脚本。10000 QPS 下这条 ERROR 不能每次都打 */
    private final Set<String> mismatchReported = ConcurrentHashMap.newKeySet();

    public EvalShaScriptExecutor(RedisTemplate<K, ?> template) {
        super(template);
    }

    /**
     * 与 {@link DefaultScriptExecutor} 的原实现只差一处：{@code NOSCRIPT} 之后
     * 走 {@code SCRIPT LOAD} + 重试 {@code EVALSHA}，不走 {@code EVAL}。
     */
    @Override
    protected <T> T eval(RedisConnection connection, RedisScript<T> script, ReturnType returnType,
                         int numKeys, byte[][] keysAndArgs, RedisSerializer<T> resultSerializer) {
        RedisScriptingCommands scripting = connection.scriptingCommands();
        Object result;
        try {
            result = scripting.evalSha(script.getSha1(), returnType, numKeys, keysAndArgs);
        } catch (Exception e) {
            if (!isNoScriptError(e)) {
                // 非 NOSCRIPT 的异常一律原样抛出：那时脚本可能<b>已经执行过</b>，
                // 当成"缓存没命中"重跑一遍就是重复扣库存。这条判定与 Spring 原实现一致
                throw e instanceof RuntimeException re ? re
                        : new RedisSystemException(e.getMessage(), e);
            }
            result = loadAndEvalSha(scripting, script, returnType, numKeys, keysAndArgs);
        }
        if (script.getResultType() == null) {
            return null;
        }
        return deserializeResult(resultSerializer, result);
    }

    /**
     * 上传正文并重试。
     *
     * <p>重试是安全的：{@code NOSCRIPT} 意味着 Redis 连脚本都没找到，一行都没执行，
     * 不存在"库存已经扣了但客户端以为没扣"的中间态。
     */
    private Object loadAndEvalSha(RedisScriptingCommands scripting, RedisScript<?> script,
                                  ReturnType returnType, int numKeys, byte[][] keysAndArgs) {
        // 正文只能从这条路上传：scriptLoad(byte[]) 原样交给 Lettuce，
        // 而 eval(byte[]) 会先 new String(bytes) 转一圈（见类注释）
        String loaded = scripting.scriptLoad(scriptBytes(script));
        if (!script.getSha1().equalsIgnoreCase(loaded) && mismatchReported.add(script.getSha1())) {
            log.error("stage=LUA_SHA_MISMATCH expected={} redis={} "
                            + "上传的正文与 getSha1() 算的不是同一串字节，EVALSHA 将持续回落，"
                            + "检查传输层是否又按平台默认编码转了一次",
                    script.getSha1(), loaded);
        }
        return scripting.evalSha(loaded == null ? script.getSha1() : loaded,
                returnType, numKeys, keysAndArgs);
    }

    /**
     * 是不是 {@code NOSCRIPT}。
     *
     * <p>判定逻辑抄自 Spring 的 {@code ScriptUtils.exceptionContainsNoScriptError}
     * ——那个类是包级私有的，抄一份比反射进去可靠。要求异常类型是
     * {@link NonTransientDataAccessException}：连接断开之类的瞬时异常绝不能被当成
     * "脚本没缓存"，那会把一次失败变成一次重复执行。
     */
    private static boolean isNoScriptError(Throwable e) {
        if (!(e instanceof NonTransientDataAccessException)) {
            return false;
        }
        for (Throwable current = e; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("NOSCRIPT")) {
                return true;
            }
        }
        return false;
    }
}

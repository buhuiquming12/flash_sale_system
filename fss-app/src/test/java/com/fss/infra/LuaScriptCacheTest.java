package com.fss.infra;

import com.fss.infra.redis.EvalShaScriptExecutor;
import com.fss.infra.redis.RedisKeys;
import com.fss.test.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实栈上的脚本缓存校验：{@code EVALSHA} 必须真的命中。
 *
 * <p>阶段五压测发现的静默退化——EVALSHA 100% 回落 EVAL、每次重传整段正文——
 * 成因是 Spring Data Redis 回落 {@code EVAL} 时按平台默认编码转了一次正文
 * （见 {@link EvalShaScriptExecutor}）。那个缺陷<b>所有集成用例照常通过</b>，
 * 因为没有一条用例问过 Redis「你缓存的是哪个 sha1」。这个类补上那句问话。
 *
 * <p>与 {@code EvalShaScriptExecutorTest} 的分工：那个用假连接钉住执行器的行为、
 * 不需要 Docker；这个用真 Redis 钉住整条链路——序列化器、连接装饰器、Lettuce 编解码，
 * 任何一环再动手脚都会在这里露头。
 */
class LuaScriptCacheTest extends IntegrationTestBase {

    @Autowired StringRedisTemplate redis;
    @Autowired ApplicationContext  ctx;

    @SuppressWarnings("rawtypes")
    @Autowired @Qualifier("seckillScript") RedisScript seckillScript;

    @Test
    @DisplayName("L1 每个脚本：Redis 对上传正文算出的 sha1 == getSha1()")
    void Redis算的SHA1与应用一致() {
        ctx.getBeansOfType(RedisScript.class).forEach((name, script) -> {
            String redisSha = redis.execute(
                    (RedisCallback<String>) c -> c.scriptingCommands().scriptLoad(bodyOf(script)));
            assertThat(redisSha)
                    .as("%s：上传的正文与 getSha1() 算的不是同一串字节，EVALSHA 必然一直回落", name)
                    .isEqualTo(script.getSha1());
        });
    }

    @Test
    @DisplayName("L2 脚本 A：冷缓存下补一次，之后不再出现 NOSCRIPT")
    void 冷缓存补一次之后一直命中() {
        coldCacheForSeckillOnly();
        long before = noScriptCount();

        callSeckill();
        assertThat(scriptExists(seckillScript.getSha1()))
                .as("Redis 缓存里必须有应用 EVALSHA 用的那个 sha1（%s）", seckillScript.getSha1())
                .isTrue();

        long afterFirst = noScriptCount();
        assertThat(afterFirst - before).as("冷缓存只允许一次 NOSCRIPT").isEqualTo(1);

        for (int i = 0; i < 5; i++) {
            callSeckill();
        }
        assertThat(noScriptCount())
                .as("补过缓存之后不该再有 NOSCRIPT，有就是又在回落 EVAL 重传正文")
                .isEqualTo(afterFirst);
    }

    /**
     * 用一个不存在的活动调脚本 A。
     *
     * <p>走到第一步「元数据未预热」就返回 {@code -1}，<b>不写任何 key</b>，
     * 所以不会污染共享容器里其它用例的数据；同时返回 -1 也证明发过去的正文是可执行的。
     */
    private void callSeckill() {
        long activityId = -1L;
        long skuId = -1L;
        Object raw = redis.execute(seckillScript,
                List.of(RedisKeys.goods(activityId, skuId),
                        RedisKeys.stock(activityId, skuId),
                        RedisKeys.bought(activityId, skuId),
                        RedisKeys.request(activityId, skuId, "lua-cache-probe")),
                "0", "1", "lua-cache-probe", "", "60");
        assertThat(raw).isInstanceOf(List.class);
        assertThat(((Number) ((List<?>) raw).get(0)).intValue())
                .as("未预热的活动应当被脚本判成 -1").isEqualTo(-1);
    }

    /**
     * 只让脚本 A 处于冷缓存状态。
     *
     * <p>其余脚本先手动装回去：{@code consumer} profile 的后台线程也在用它们
     * （回补、写结果、令牌桶），让它们跟着冷会把 {@code errorstat_NOSCRIPT} 的计数搅浑，
     * 用例就变成间歇性失败了。
     */
    private void coldCacheForSeckillOnly() {
        redis.execute((RedisCallback<Object>) c -> {
            c.scriptingCommands().scriptFlush();
            return null;
        });
        ctx.getBeansOfType(RedisScript.class).values().stream()
                .filter(script -> script != seckillScript)
                .forEach(script -> redis.execute(
                        (RedisCallback<String>) c -> c.scriptingCommands().scriptLoad(bodyOf(script))));
    }

    /** 与 {@code DefaultScriptExecutor.scriptBytes()} 同一条编码路径 */
    private byte[] bodyOf(RedisScript<?> script) {
        return redis.getStringSerializer().serialize(script.getScriptAsString());
    }

    private boolean scriptExists(String sha1) {
        List<Boolean> found = redis.execute(
                (RedisCallback<List<Boolean>>) c -> c.scriptingCommands().scriptExists(sha1));
        return found != null && !found.isEmpty() && Boolean.TRUE.equals(found.get(0));
    }

    /** {@code INFO errorstats} 里的 {@code errorstat_NOSCRIPT:count=N}；没这一行就是一次都没发生 */
    private long noScriptCount() {
        Properties info = redis.execute(
                (RedisCallback<Properties>) c -> c.serverCommands().info("errorstats"));
        String value = info == null ? null : info.getProperty("errorstat_NOSCRIPT");
        return value == null ? 0L : Long.parseLong(value.substring(value.indexOf('=') + 1).trim());
    }
}

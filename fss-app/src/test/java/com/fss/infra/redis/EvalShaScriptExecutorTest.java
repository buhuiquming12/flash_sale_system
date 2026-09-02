package com.fss.infra.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 脚本缓存必须真的命中：一次 {@code SCRIPT LOAD} 之后，后续调用全部走 {@code EVALSHA}。
 *
 * <h3>为什么这里用假连接而不是真 Redis</h3>
 * 要断言的是「客户端发出了哪几条命令、正文的字节是什么」，真 Redis 只能从
 * {@code INFO commandstats} 事后反推，而且得起容器。这个假连接把
 * <b>Spring Data Redis 的 Lettuce 适配层那次有损转码</b>也一起模型化了
 * （见 {@link FakeRedis#eval}），于是原缺陷可以被直接复现（L2）——
 * 有 L2 兜着，L1 的绿灯才有意义。真实栈上的端到端校验在
 * {@code LuaScriptCacheTest}（需要 Docker）。
 */
class EvalShaScriptExecutorTest {

    /** 与压测机一致的平台默认编码。写死的理由见 {@link FakeRedis} */
    private static final Charset PLATFORM_DEFAULT = Charset.forName("GBK");

    @Test
    @DisplayName("L1 换上 EvalShaScriptExecutor：只有首次补缓存，之后 EVALSHA 命中，不发 EVAL")
    void 补缓存之后EVALSHA能命中() {
        FakeRedis redis = new FakeRedis();
        StringRedisTemplate template = templateOn(redis);
        template.setScriptExecutor(new EvalShaScriptExecutor<>(template));
        RedisScript<Long> script = new LuaScriptConfig().tokenBucketScript();

        for (int i = 0; i < 3; i++) {
            assertThat(template.execute(script, List.of("k"), "10", "10", "1")).isEqualTo(1L);
        }

        assertThat(redis.noScriptErrors).as("只有第一次调用需要补缓存").isEqualTo(1);
        assertThat(redis.evalCalls)
                .as("EVAL 一条都不能发：那条路会按平台默认编码改写正文的字节")
                .isZero();
        assertThat(redis.cache)
                .as("Redis 缓存的 key 必须就是应用 EVALSHA 用的那个 sha1")
                .containsKey(script.getSha1());
    }

    @Test
    @DisplayName("L2 Spring 默认执行器在同一个假连接上复现原缺陷：每次都 NOSCRIPT + 回落 EVAL")
    void 默认执行器会一直回落EVAL() {
        FakeRedis redis = new FakeRedis();
        StringRedisTemplate template = templateOn(redis);     // 保留构造函数装的默认执行器
        RedisScript<Long> script = new LuaScriptConfig().tokenBucketScript();

        for (int i = 0; i < 3; i++) {
            template.execute(script, List.of("k"), "10", "10", "1");
        }

        // 这就是阶段五压测看到的样子：failed_calls == calls，正文每次重传
        assertThat(redis.noScriptErrors).as("每次调用都 NOSCRIPT").isEqualTo(3);
        assertThat(redis.evalCalls).as("每次都回落 EVAL 重传正文").isEqualTo(3);
        assertThat(redis.cache)
                .as("EVAL 隐式缓存落在转码后正文的 sha1 上，与应用发出的 sha1 永远不相等")
                .doesNotContainKey(script.getSha1());
    }

    @Test
    @DisplayName("L3 装配：Boot 的自动装配退让，容器里的 StringRedisTemplate 用的是本执行器")
    void 执行器真的装到模板上了() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withUserConfiguration(RedisTemplateConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(StringRedisTemplate.class);
                    assertThat(ReflectionTestUtils.getField(
                            context.getBean(StringRedisTemplate.class), "scriptExecutor"))
                            .as("执行器没装上去的话 EVALSHA 会重新 100% 回落 EVAL，而且照样一声不响")
                            .isInstanceOf(EvalShaScriptExecutor.class);
                });
    }

    /**
     * 把脚本相关的调用接到假 Redis 上；其余方法交给 Mockito 的默认返回值。
     *
     * <p>{@code StringRedisTemplate} 会用 {@code DefaultStringRedisConnection} 把连接包一层，
     * 那一层对 {@code scriptLoad(byte[])} 是原样转发（{@code identityConverter}），
     * 所以这些 case 拦到的就是真实栈上传给 Lettuce 的那串字节。
     */
    private static StringRedisTemplate templateOn(FakeRedis redis) {
        RedisConnection connection = mock(RedisConnection.class, invocation ->
                switch (invocation.getMethod().getName()) {
                    case "scriptingCommands" -> redis;
                    case "scriptLoad" -> redis.scriptLoad(invocation.getArgument(0));
                    // 默认执行器直接调连接上的这两个（只用得到 String digest 那个重载）
                    case "evalSha" -> redis.evalSha((String) invocation.getArgument(0), null, 0);
                    case "eval" -> redis.eval(invocation.getArgument(0), null, 0);
                    default -> Answers.RETURNS_DEFAULTS.answer(invocation);
                });
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        return new StringRedisTemplate(factory);
    }

    /**
     * 假 Redis：一个脚本缓存，加上 Lettuce 适配层那一次有损转码。
     *
     * <p>转码用的默认编码<b>写死成 GBK</b>，不读 {@code Charset.defaultCharset()}：
     * surefire 给测试 JVM 加了 {@code -Dfile.encoding=UTF-8}，照实读的话这一步会退化成
     * 恒等变换，L2 永远绿灯 —— 而「测试 JVM 的编码和运行时不一样」正是这个缺陷
     * 能活过整个测试套件、只在压测时露头的原因。
     */
    private static final class FakeRedis implements RedisScriptingCommands {

        /** sha1(收到的原字节) → 正文，模拟 Redis 的脚本缓存 */
        final Map<String, byte[]> cache = new HashMap<>();
        int evalCalls;
        int noScriptErrors;

        @Override
        public String scriptLoad(byte[] script) {
            String sha = sha1(script);                 // Redis 按收到的原字节算 sha1
            cache.put(sha, script);
            return sha;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T eval(byte[] script, ReturnType returnType, int numKeys, byte[]... keysAndArgs) {
            evalCalls++;
            // LettuceConverters.toString(byte[]) 是 new String(bytes)，按平台默认编码解码；
            // Lettuce 的 encodeScript 再按 ClientOptions.scriptCharset（默认 UTF-8）编回去
            byte[] onTheWire = new String(script, PLATFORM_DEFAULT).getBytes(StandardCharsets.UTF_8);
            cache.put(sha1(onTheWire), onTheWire);     // EVAL 的隐式缓存
            return (T) Long.valueOf(1L);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T evalSha(String sha, ReturnType returnType, int numKeys, byte[]... keysAndArgs) {
            if (!cache.containsKey(sha)) {
                noScriptErrors++;
                // 与 Lettuce 抛上来的形状一致：NOSCRIPT 在 cause 里，不在最外层 message
                throw new RedisSystemException("Error in execution",
                        new IllegalStateException("NOSCRIPT No matching script"));
            }
            return (T) Long.valueOf(1L);
        }

        @Override
        public <T> T evalSha(byte[] sha, ReturnType returnType, int numKeys, byte[]... keysAndArgs) {
            return evalSha(new String(sha, StandardCharsets.UTF_8), returnType, numKeys, keysAndArgs);
        }

        @Override
        public List<Boolean> scriptExists(String... shas) {
            List<Boolean> exists = new ArrayList<>(shas.length);
            for (String sha : shas) {
                exists.add(cache.containsKey(sha));
            }
            return exists;
        }

        @Override
        public void scriptFlush() {
            cache.clear();
        }

        @Override
        public void scriptKill() {
        }
    }

    private static String sha1(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder hex = new StringBuilder(40);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                   .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

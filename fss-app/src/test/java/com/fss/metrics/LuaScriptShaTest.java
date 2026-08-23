package com.fss.metrics;

import com.fss.infra.redis.LuaScriptConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.RedisScript;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lua 脚本的 SHA1 必须与「上传给 Redis 的正文」一致。
 *
 * <h3>这个类防的是什么</h3>
 * 阶段五压测实测到一个**静默性能退化**：一次秒杀请求发出
 * 2 次 {@code EVALSHA}（全部 NOSCRIPT）+ 4 次 {@code EVAL}，
 * 每次 Lua 调用都多一个 RTT 并重传整段脚本正文（seckill.lua 有 3.4KB）。
 * 功能完全正确——回落到 EVAL 本来就是设计好的兜底，所有集成用例照常通过，
 * 也没有任何日志或告警。
 *
 * <p>这个类**不负责修那个缺陷**（成因还在查，见 {@link LuaScriptConfig} 的类注释），
 * 它负责把「本类这一侧」永久钉住：
 * <b>{@code getSha1()} 必须等于 {@code sha1(getScriptAsString())}</b> ——
 * 这正是 Redis 那边成立的等式，Redis 对脚本原字节算出的 sha1hex
 * 实测就等于这个值。有了它，排查时可以直接排除
 * 「装配侧算错了 SHA」这一类成因，把范围缩到执行器与连接层。
 *
 * <p>顺带防住两类回归：把 {@code setScriptText} 改回
 * {@code setScriptSource(ResourceScriptSource)} 之后正文被 trim（L2），
 * 以及复制 Bean 时忘改文件路径（L3）。
 *
 * <p>不需要容器：这是纯粹的字符串与摘要性质。
 */
class LuaScriptShaTest {

    /** 与 LuaScriptConfig 里的 5 个 Bean 一一对应 */
    private static final List<String> SCRIPTS = List.of(
            "lua/seckill.lua",
            "lua/rollback.lua",
            "lua/release.lua",
            "lua/write_result.lua",
            "lua/token_bucket.lua");

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                  .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = LuaScriptShaTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("脚本 %s 不在 classpath 上", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("L1 每个脚本的 getSha1() == sha1(实际上传的正文)")
    void SHA1与上传正文一致() {
        LuaScriptConfig cfg = new LuaScriptConfig();
        List<RedisScript<?>> scripts = List.of(
                cfg.seckillScript(),
                cfg.rollbackScript(),
                cfg.releaseScript(),
                cfg.writeResultScript(),
                cfg.tokenBucketScript());

        for (RedisScript<?> s : scripts) {
            String body = s.getScriptAsString();
            assertThat(s.getSha1())
                    .as("getSha1() 与 sha1(正文) 不等 —— EVALSHA 会永远 NOSCRIPT，"
                            + "每次调用回落 EVAL 重传整段脚本。"
                            + "常见成因是改回了 setScriptSource(ResourceScriptSource)")
                    .isEqualTo(sha1(body));
        }
    }

    @Test
    @DisplayName("L2 脚本正文与 resources 里的文件逐字节一致（没有被 trim 掉尾换行）")
    void 正文未被修改() throws IOException {
        LuaScriptConfig cfg = new LuaScriptConfig();
        List<RedisScript<?>> scripts = List.of(
                cfg.seckillScript(),
                cfg.rollbackScript(),
                cfg.releaseScript(),
                cfg.writeResultScript(),
                cfg.tokenBucketScript());

        for (int i = 0; i < SCRIPTS.size(); i++) {
            String path = SCRIPTS.get(i);
            assertThat(scripts.get(i).getScriptAsString())
                    .as("%s 的正文与文件不一致。尾部换行、BOM、CRLF 都会改变 SHA1，"
                            + "而 SHA1 一变 EVALSHA 就再也命中不了", path)
                    .isEqualTo(readResource(path));
        }
    }

    @Test
    @DisplayName("L3 五个脚本的 SHA1 互不相同（防止 Bean 复制粘贴指向同一个文件）")
    void 五个脚本各不相同() {
        LuaScriptConfig cfg = new LuaScriptConfig();
        List<String> shas = List.of(
                cfg.seckillScript().getSha1(),
                cfg.rollbackScript().getSha1(),
                cfg.releaseScript().getSha1(),
                cfg.writeResultScript().getSha1(),
                cfg.tokenBucketScript().getSha1());
        // 复制 Bean 时忘改路径的话，两个脚本会指向同一个文件 ——
        // 而那种错误在功能上表现为「回补脚本其实执行了判扣逻辑」，非常难查
        assertThat(shas).doesNotHaveDuplicates();
    }
}

package com.fss.test;

import com.fss.app.FssApplication;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基类。
 *
 * <p><b>用 Testcontainers 起真实 MySQL 与真实 Redis，不用 H2、不用嵌入式 Redis。</b>
 * H2 的 {@code CHECK} 约束、行锁语义、唯一键冲突时的错误信息都和 MySQL 不同；
 * 嵌入式 Redis（embedded-redis 之类）对 Lua 的支持是残缺的——
 * {@code redis.call('TIME')} 在写脚本里能不能用、返回值精度多少，
 * 各家实现都不一样。而这两样恰好是本项目防超卖的全部依赖：
 * 在替代品上测过了不代表真环境对。
 *
 * <p>容器用 {@code static} 且不显式 stop：Testcontainers 的 ryuk 会在 JVM 退出时
 * 清理，多个测试类共享同一个容器，避免每个类都付一次启动时间。
 *
 * <p>{@code classes = FssApplication.class} 是必需的：测试类在 {@code com.fss.seckill}
 * 等包下，Spring Boot 从测试包逐级向上找 {@code @SpringBootConfiguration}，
 * 而启动类在 {@code com.fss.app} —— 找不到就报
 * "Unable to find a @SpringBootConfiguration"。
 *
 * <p>profile 故意<b>不</b>包含 {@code job}：定时任务在测试中途把订单关掉会造成
 * 难以复现的间歇性失败。需要验证关单逻辑的用例直接调 Service。
 */
@Tag("integration")
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(classes = FssApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestFixture.class)
public abstract class IntegrationTestBase {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("flash_sale")
            .withUsername("root")
            .withPassword("root")
            // 与 docker-compose 的参数保持一致，避免"测试过了但部署环境行为不同"。
            // default-time-zone 尤其重要：容器默认 UTC，而应用按 Asia/Shanghai
            // 写入 LocalDateTime，不统一会让 NOW(3) 生成的时间比 Java 写入的慢 8 小时。
            .withCommand("--character-set-server=utf8mb4",
                         "--collation-server=utf8mb4_general_ci",
                         "--default-time-zone=+08:00",
                         "--max-connections=500")
            .withReuse(true);

    /**
     * Redis 容器。
     *
     * <p>{@code maxmemory-policy noeviction} 与生产一致：秒杀库存和用户购买标记
     * 绝不能被淘汰。测试里内存压力不可能触发淘汰，但配置漂移本身就是隐患——
     * 测试环境和部署环境用同一套参数，才谈得上"测过了"。
     */
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--maxmemory-policy", "noeviction")
                    .withReuse(true);

    static {
        MYSQL.start();
        REDIS.start();
        flushRedis();
    }

    /**
     * 清空 Redis。
     *
     * <p>开启容器复用（{@code testcontainers.reuse.enable=true}）时容器会跨多次
     * {@code mvn test} 存活，上一轮遗留的 {@code seckill:stock} 之类的 key
     * 会让本轮出现莫名其妙的失败。清一次的代价是零，不清的代价是花半天查一个
     * "只在第二次运行时失败"的问题。
     */
    private static void flushRedis() {
        try {
            REDIS.execInContainer("redis-cli", "flushall");
        } catch (Exception e) {
            throw new IllegalStateException("清空测试 Redis 失败", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}

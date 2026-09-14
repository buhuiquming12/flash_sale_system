package com.fss.test;

import com.fss.app.FssApplication;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 集成测试基类。
 *
 * <p><b>用 Testcontainers 起真实 MySQL、真实 Redis、真实 RocketMQ，
 * 不用 H2、不用嵌入式 Redis、不用 Mock 的 RocketMQTemplate。</b>
 * H2 的 {@code CHECK} 约束、行锁语义、唯一键冲突时的错误信息都和 MySQL 不同；
 * 嵌入式 Redis 对 Lua 的支持是残缺的（{@code redis.call('TIME')} 在写脚本里
 * 能不能用、返回值精度多少，各家实现都不一样）；而 Mock 掉 MQ 之后，
 * 阶段三真正要验的那些东西——重复投递、消费失败重试、进死信、定时消息按时投递——
 * 一个都验不到，剩下的只是"我调用了 send 方法"。
 *
 * <p>容器用 {@code static} 且不显式 stop：Testcontainers 的 ryuk 会在 JVM 退出时
 * 清理，多个测试类共享同一套容器，避免每个类都付一次启动时间（RocketMQ 尤其贵）。
 *
 * <h3>为什么 RocketMQ 不能用 Testcontainers 的随机端口</h3>
 * broker 把 {@code brokerIP1:listenPort} 注册到 namesrv，客户端从 namesrv
 * 查到这个地址后<b>直连 broker</b>。随机映射时客户端拿到的是容器内端口，连不上——
 * 症状是发送超时，而日志里只说 "sendDefaultImpl call timeout"，
 * 完全看不出是端口的问题。所以这里固定宿主端口，并刻意错开
 * docker-compose 用的 9876/10911，让 {@code mvn verify} 和联调环境能同时活着。
 *
 * <p>profile 包含 {@code consumer}：阶段三起订单是消费端建的，
 * 不激活它的话所有秒杀都会永远停在"排队中"。仍然<b>不</b>包含 {@code job}——
 * 定时任务在测试中途把订单关掉会造成难以复现的间歇性失败，
 * 需要它的用例自己加（见 {@code ScheduledJobTest}）。
 */
@Tag("integration")
@Testcontainers
@ActiveProfiles({"test", "consumer"})
@SpringBootTest(classes = FssApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestFixture.class)
public abstract class IntegrationTestBase {

    /** 宿主侧端口，刻意错开 docker-compose 用的 9876 / 10911 */
    private static final int MQ_NAMESRV_PORT = 9877;
    private static final int MQ_BROKER_PORT  = 10921;
    /** namesrv 在容器里的监听端口写死在 mqnamesrv 脚本里，改不了 */
    private static final int MQ_NAMESRV_CONTAINER_PORT = 9876;

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

    /**
     * MQ 专用的用户自定义网络。
     *
     * <p>必须显式建而不是用默认 bridge：默认 bridge 里<b>没有 DNS</b>，
     * broker 解析不了 namesrv 的别名。用户自定义网络才有内置 DNS。
     */
    private static final Network MQ_NET = Network.newNetwork();

    static final GenericContainer<?> MQ_NAMESRV =
            new GenericContainer<>(DockerImageName.parse("apache/rocketmq:5.3.0"))
                    .withNetwork(MQ_NET)
                    // broker-test.conf 里 namesrvAddr = mq-namesrv:9876 靠这个别名解析
                    .withNetworkAliases("mq-namesrv")
                    .withCommand("sh", "mqnamesrv")
                    // 默认 -Xms4g，普通开发机直接 OOM 起不来
                    .withEnv("JAVA_OPT_EXT", "-Xms128m -Xmx256m -Xmn128m")
                    .withCreateContainerCmdModifier(
                            fixedPort(MQ_NAMESRV_PORT, MQ_NAMESRV_CONTAINER_PORT))
                    .waitingFor(Wait.forLogMessage(".*Name Server boot success.*\\n", 1)
                            .withStartupTimeout(Duration.ofMinutes(3)));

    static final GenericContainer<?> MQ_BROKER =
            new GenericContainer<>(DockerImageName.parse("apache/rocketmq:5.3.0"))
                    .withNetwork(MQ_NET)
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("rocketmq/broker-test.conf"),
                            "/home/rocketmq/broker-test.conf")
                    .withCommand("sh", "mqbroker", "-c", "/home/rocketmq/broker-test.conf")
                    .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx512m -Xmn128m")
                    .withCreateContainerCmdModifier(
                            // broker 的 listenPort 由 broker-test.conf 指定为 10921，
                            // 容器内外同号，所以这里两个参数一样
                            fixedPort(MQ_BROKER_PORT, MQ_BROKER_PORT))
                    .waitingFor(Wait.forLogMessage(".*boot success.*\\n", 1)
                            .withStartupTimeout(Duration.ofMinutes(3)));

    static {
        MYSQL.start();
        REDIS.start();
        // 顺序：broker 启动时要连 namesrv 注册自己
        MQ_NAMESRV.start();
        MQ_BROKER.start();
        flushRedis();
        assertMqReachable();
    }

    /**
     * 断言两个 MQ 容器的宿主端口真的能连上。
     *
     * <p><b>为什么必须要这一步。</b>容器起来但端口连不上的症状极具迷惑性：
     * 每一次 {@code ReliableMqProducer.sendReliable} 抛
     * {@code RemotingConnectException}，失败的请求停在 PENDING，而
     * {@code TestFixture.AWAIT} 是"超时才抛"的轮询——于是<b>每一个</b>
     * {@code submitAndAwait} / {@code awaitOrders} 都烧满整整 60 秒才失败。
     *
     * <p>CI 上真发生过一次：broker 的 127.0.0.1:10921 全程无监听（2344 次
     * {@code RemotingConnectException}），而 namesrv 的 9877 一次都没失败。
     * 后果是 9 个测试类、37 个用例连环失败，<b>纯等待就烧掉 36 分钟</b>，
     * 把 job 的 40 分钟超时吃满，而日志里没有一行直接说"broker 连不上"。
     *
     * <p>所以这里在容器刚起来时就主动连一次：连不上立刻抛，几秒内失败，
     * 而不是让 18 个测试类各自慢慢烧完 60 秒。
     *
     * <p>先查容器死活再查端口，是为了把两种原因分开——"启动后崩了"和
     * "端口没发布"要看的日志完全不同，混在一起猜会浪费很多时间。
     */
    private static void assertMqReachable() {
        assertRunning(MQ_NAMESRV, "namesrv");
        assertRunning(MQ_BROKER, "broker");
        assertTcp(MQ_NAMESRV, MQ_NAMESRV_PORT, MQ_NAMESRV_CONTAINER_PORT, "namesrv");
        assertTcp(MQ_BROKER, MQ_BROKER_PORT, MQ_BROKER_PORT, "broker");
    }

    /** 容器还活着吗。分开判才能区分"崩了"和"端口没发布" */
    private static void assertRunning(GenericContainer<?> c, String what) {
        if (!c.isRunning()) {
            throw new IllegalStateException(
                    what + " 容器启动后已经不在运行了。看容器日志找崩溃原因"
                            + "（docker logs " + c.getContainerId() + "），"
                            + "RocketMQ broker 的日志默认写在容器内 ~/logs/rocketmqlogs/ 下");
        }
    }

    private static void assertTcp(GenericContainer<?> c, int hostPort, int containerPort,
                                  String what) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", hostPort), 5000);
        } catch (Exception e) {
            throw new IllegalStateException(
                    what + " 的宿主端口 127.0.0.1:" + hostPort + " 连不上，集成测试必然"
                            + "全军覆没（每个异步等待都会烧满 60 秒）。容器还在运行却连不上，"
                            + "说明端口没发布——检查 " + what + " 的 fixedPort 是否生效"
                            + "（docker port " + what + " 容器 id），以及端口是否被别的东西占了。"
                            + "Docker 侧的实际情况：" + publishState(c, containerPort), e);
        }
    }

    /**
     * 失败路径上的诊断：Docker 到底把容器端口 <b>发布</b> 到宿主哪个端口了。
     *
     * <p>{@code getMappedPort} 读的是 {@code docker inspect} 的 NetworkSettings，
     * 也就是 daemon 的最终结论，不是我们的请求——所以它能回答
     * "PortBindings 是不是被 dockerd 丢掉了"这个从 Java 侧看不见的问题。
     *
     * <p>只做诊断：它自己出任何错都不能把原始异常盖掉，所以全部吞掉。
     */
    private static String publishState(GenericContainer<?> c, int containerPort) {
        try {
            return "容器端口 " + containerPort + "/tcp 被 daemon 发布在宿主 "
                    + c.getMappedPort(containerPort) + "（若不是我们要的端口，"
                    + "或这里抛异常说没有映射，就是 PortBindings 被丢弃了）";
        } catch (Exception e) {
            return "daemon 没有为容器端口 " + containerPort + "/tcp 建立任何映射（"
                    + e.getMessage() + "）。最常见的原因是该端口不在镜像的 EXPOSE 里——"
                    + "见 fixedPort 的注释";
        }
    }

    /**
     * 宿主端口 → 容器端口的<b>固定</b>映射。见类注释里为什么不能随机映射。
     *
     * <p>两个端口号刻意分开传：namesrv 在容器里永远监听 9876（写死在
     * {@code mqnamesrv} 里），而宿主侧要用 9877 才不跟 docker-compose 的那套撞。
     *
     * <h4>为什么必须连 EXPOSE 一起设</h4>
     * <b>只设 {@code PortBindings} 在原生 Linux dockerd 上不生效</b>：
     * Docker 只发布容器 EXPOSE 过的端口，没 EXPOSE 的绑定会被<b>静默丢弃</b>——
     * 容器照常启动、broker 照常打 "boot success"，只有宿主端口没人监听。
     *
     * <p>这正是 CI 上"namesrv 的 9877 好、broker 的 10921 连不上"的原因：
     * 镜像 apache/rocketmq:5.3.0 只 EXPOSE 了 9876/10909/10911/10912。
     * namesrv 用的容器端口 9876 在列表里，所以绑定生效；broker 用的 10921 不在，
     * 所以绑定被丢掉。Docker Desktop 的端口转发不校验 EXPOSE，于是这个问题
     * <b>只在 CI 上暴露</b>——本地跑 mvn verify 一路绿，极具迷惑性。
     * （早先那次 9877→9877 的故障八成也是同一个原因：9877 不在 EXPOSE 里，
     * 换成 9876 就好了，当时归因成了"映射到了一个空端口"。）
     *
     * <p>放在 {@code withCreateContainerCmdModifier} 里而不是
     * {@code withExposedPorts}：后者会把端口交给 Testcontainers 随机分配宿主端口，
     * 而 broker 必须用固定端口（见类注释）。
     */
    private static Consumer<CreateContainerCmd> fixedPort(int hostPort, int containerPort) {
        return cmd -> {
            ExposedPort exposed = new ExposedPort(containerPort);
            cmd.withExposedPorts(exposed);
            cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(hostPort), exposed));
        };
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
        registry.add("rocketmq.name-server", () -> "127.0.0.1:" + MQ_NAMESRV_PORT);
    }
}

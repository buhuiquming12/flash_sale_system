package com.fss.infra.mq;

import com.fss.infra.config.FssProperties;
import com.fss.infra.metrics.SeckillMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息积压量采集。
 *
 * <p>积压量 = 每个队列的 {@code maxOffset - consumerOffset} 之和。它是异步化系统里
 * <b>最重要的一个指标</b>：秒杀接口的成功率、Redis 库存、DB 订单数都可以完全正常，
 * 而积压 5 万条意味着 5 万个用户正卡在"排队中"、5 万份库存被占着。
 * 没有这条曲线，"消费端跟不上"这件事只能靠用户投诉发现。
 *
 * <h3>三个必须注意的实现细节</h3>
 * <ol>
 *   <li><b>{@link DefaultMQAdminExt} 自己是一个 MQ 客户端</b>，有 start/shutdown 生命周期，
 *       且实例化时会枚举本机网卡。所以它必须是单例、懒启动、可整体关掉——
 *       集成测试里多开一个 MQ 客户端在 Windows 上会直接撞
 *       {@code GetAdaptersAddresses failed with error == 1450}
 *       （ERROR_NO_SYSTEM_RESOURCES，Docker Desktop 的虚拟网卡太多）。</li>
 *   <li><b>{@code instanceName} 必须显式设定且与生产者/消费者不同。</b> RocketMQ 客户端按
 *       {@code clientId = ip@instanceName} 复用底层 {@code MQClientInstance}，
 *       撞名会让 admin 与业务客户端共享一个实例，shutdown 时互相踩。</li>
 *   <li><b>采集失败绝不能抛。</b> 它跑在定时任务里，抛出去只会让整个监控任务中断，
 *       而"监控挂了"本身是无声的——下游只会看到曲线断掉，分不清是没积压还是没采到。
 *       所以失败返回 {@code null}，由调用方决定是"保持上次值"还是"跳过这一轮"。</li>
 * </ol>
 *
 * <p><b>默认关闭</b>（{@code fss.mq.backlog-monitor-enabled}）。理由不是它有风险，
 * 而是它需要 broker 可达才有意义：本地只起了 MySQL/Redis 时打开它会每 15 秒刷一条
 * 连接失败日志，把真正的错误埋掉。生产与联调环境显式打开。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "fss.mq.backlog-monitor-enabled", havingValue = "true")
public class MqBacklogMonitor {

    private final FssProperties props;
    private final SeckillMetrics metrics;
    private final String nameServer;

    /**
     * volatile + 双检锁懒初始化：定时任务是单线程的，但管理接口也可能来查一次，
     * 而 {@code start()} 只能调一次。
     */
    private volatile DefaultMQAdminExt admin;

    public MqBacklogMonitor(FssProperties props, SeckillMetrics metrics,
                            @Value("${rocketmq.name-server}") String nameServer) {
        this.props = props;
        this.metrics = metrics;
        this.nameServer = nameServer;
    }

    /**
     * 采集全部四个消费组的积压量并上报仪表。
     *
     * @return 订单创建组的积压量（自动降级的判据）；采集失败返回 {@code null}
     */
    public Long collect() {
        List<Pair> pairs = List.of(
                new Pair(props.getMq().getTopic().getOrderCreate(),
                        props.getMq().getGroup().getOrderCreate()),
                new Pair(props.getMq().getTopic().getOrderClose(),
                        props.getMq().getGroup().getOrderClose()),
                new Pair(props.getMq().getTopic().getStockRelease(),
                        props.getMq().getGroup().getStockRelease()),
                new Pair(props.getMq().getTopic().getStockRollback(),
                        props.getMq().getGroup().getStockRollback()));

        List<SeckillMetrics.BacklogView> views = new ArrayList<>(pairs.size());
        Long orderCreateLag = null;
        for (Pair p : pairs) {
            Long lag = lagOf(p.topic(), p.group());
            if (lag == null) {
                continue;
            }
            views.add(new SeckillMetrics.BacklogView(p.topic(), p.group(), lag));
            if (p.topic().equals(props.getMq().getTopic().getOrderCreate())) {
                orderCreateLag = lag;
            }
        }
        if (!views.isEmpty()) {
            metrics.publishBacklogGauges(views);
        }
        return orderCreateLag;
    }

    /**
     * 单个消费组的积压量。
     *
     * <p>用 {@code examineConsumeStats(group, topic)} 而不是只传 group：只传 group 时
     * 返回该组订阅的<b>全部</b> Topic 的统计，把重试 Topic（{@code %RETRY%group}）
     * 也算进来——于是"有几条消息在重试"会被读成"有积压"，而重试是正常的。
     */
    public Long lagOf(String topic, String group) {
        try {
            DefaultMQAdminExt a = client();
            var stats = a.examineConsumeStats(group, topic);
            long lag = 0;
            for (var e : stats.getOffsetTable().entrySet()) {
                // brokerOffset 是队列的最大位点，consumerOffset 是已消费位点。
                // 差为负说明位点被重置过（比如 CONSUME_FROM_LAST_OFFSET 的新组），
                // 按 0 计——报负数积压只会让告警规则和看板都乱掉
                lag += Math.max(0, e.getValue().getBrokerOffset() - e.getValue().getConsumerOffset());
            }
            return lag;
        } catch (Exception e) {
            // 消费组还没建立（从未有实例上线）时这里会抛，属于正常情况而非故障，
            // 所以是 debug 而不是 error —— 空活动期间每 15 秒刷一条 error
            // 会把真正的问题埋掉
            log.debug("stage=MQ_BACKLOG topic={} group={} result=UNAVAILABLE", topic, group, e);
            return null;
        }
    }

    private DefaultMQAdminExt client() throws Exception {
        DefaultMQAdminExt local = admin;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (admin == null) {
                DefaultMQAdminExt a = new DefaultMQAdminExt(new AclClientRPCHook(
                        new SessionCredentials(props.getMq().getAccessKey(),
                                props.getMq().getSecretKey())), 5000);
                a.setNamesrvAddr(nameServer);
                // 见类注释：必须与生产者/消费者的 instanceName 区分开
                a.setInstanceName("fss-backlog-monitor");
                a.setAdminExtGroup("GID_FSS_ADMIN_EXT");
                a.start();
                admin = a;
                log.info("stage=MQ_BACKLOG admin 客户端已启动 nameServer={}", nameServer);
            }
            return admin;
        }
    }

    /**
     * 必须显式 shutdown。
     *
     * <p>不关的话 admin 客户端的心跳线程会拖住 JVM 退出——表现为
     * "应用日志已经打完 Shutdown completed，进程却不退"，
     * 而这在容器里等于每次滚动更新都要等 SIGKILL。
     */
    @PreDestroy
    public void close() {
        DefaultMQAdminExt local = admin;
        if (local != null) {
            try {
                local.shutdown();
            } catch (Exception e) {
                log.debug("admin 客户端关闭失败", e);
            }
            admin = null;
        }
    }

    /** Topic 与其消费组的配对。四个 Topic 各查一次，一次采集覆盖全部消费链路 */
    private record Pair(String topic, String group) {
    }
}

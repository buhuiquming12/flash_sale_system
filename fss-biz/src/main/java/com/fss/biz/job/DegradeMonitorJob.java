package com.fss.biz.job;

import com.fss.infra.alarm.AlarmService;
import com.fss.infra.config.FssProperties;
import com.fss.infra.degrade.DegradeSwitch;
import com.fss.infra.lock.DistributedLock;
import com.fss.infra.mq.MqBacklogMonitor;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动降级监控。job 角色跑，把算出来的等级写进 Redis，各 web 实例每秒读一次。
 *
 * <h3>目标等级 = 各条件的最大值，而不是"最后一个触发的条件"</h3>
 * 这是自动降级最容易写错的地方。逐条件顺序判断、后面的覆盖前面的，会出现：
 * 积压超阈值判定 Level 3，接着"连接池正常"这一条把它写回 0——秒杀在积压 8 万条的
 * 情况下重新开闸。取最大值则天然满足"任何一个条件要求收紧，就必须收紧"。
 *
 * <p>取最大值还顺带解决了<b>自动恢复</b>：每轮从 0 重新算一遍，
 * 所有条件都退回时结果自然是 0，不需要额外记"是谁触发的、什么时候能解除"。
 * 无状态的算法在多实例、重启、切主之后都还是对的。
 *
 * <h3>滞回是必须的</h3>
 * 恢复阈值 = 触发阈值 × {@code recover-ratio}（默认 0.5）。没有滞回的话：
 * 积压 50001 → 降级 Level 3 → 不再放新资格 → 消费端追上变 49999 → 恢复 →
 * 流量立刻回来又 50001 → 再降级。每次抖动都是一次全站行为变化，
 * 客户端的轮询间隔在 300ms 和 2000ms 之间来回跳，用户体验比一直降级还差。
 *
 * <p>实现上滞回<b>依赖当前等级</b>：已经在降级中时用恢复阈值，没降级时用触发阈值。
 * 所以这个方法不是纯函数——它必须读 {@link DegradeSwitch#getLevel()}。
 *
 * <h3>为什么不接"秒杀接口错误率"这一条</h3>
 * docs/07 §4 列了"错误率 > 20% 持续 30s → Level 2"。它需要在应用内维护一个
 * 30 秒滑动窗口的成功/失败计数，而这件事 Sentinel 的慢调用比例熔断已经在做了
 * （{@code seckill:submit}，RT > 200ms 占比 > 50% 熔断 10 秒），
 * Prometheus 侧也有 {@code SeckillErrorRateHigh} 告警规则。
 * 在第三个地方再实现一遍同样的窗口统计，只会多一处可能与另两处不一致的判断。
 * 这里只接<b>那些别处没有的信号</b>：MQ 积压与连接池等待。
 */
@Slf4j
@Component
@Profile("job")
public class DegradeMonitorJob {

    private final DegradeSwitch    degradeSwitch;
    private final FssProperties    props;
    private final AlarmService     alarm;
    /**
     * 积压监控可能没启用（{@code fss.mq.backlog-monitor-enabled=false}），
     * 用 ObjectProvider 而不是 {@code @Autowired(required=false)}：后者在字段上，
     * 而这里是构造器注入，且 ObjectProvider 能明确表达"这个依赖可以缺席"。
     */
    private final ObjectProvider<MqBacklogMonitor> backlogMonitor;
    private final ObjectProvider<DataSource>       dataSource;

    /** 消费端"卡住"的连续轮次。积压 > 0 但一轮都没消费掉，累计到阈值判 Level 3 */
    private long lastBacklog = -1;
    private int  stalledRounds;

    public DegradeMonitorJob(DegradeSwitch degradeSwitch, FssProperties props,
                             AlarmService alarm,
                             ObjectProvider<MqBacklogMonitor> backlogMonitor,
                             ObjectProvider<DataSource> dataSource) {
        this.degradeSwitch = degradeSwitch;
        this.props = props;
        this.alarm = alarm;
        this.backlogMonitor = backlogMonitor;
        this.dataSource = dataSource;
    }

    @Scheduled(fixedDelayString = "${fss.job.degrade-monitor-delay-ms:15000}")
    @DistributedLock(key = "degrade-monitor", leaseSeconds = 60)
    public void monitor() {
        if (!props.getDegrade().isAutoEnabled()) {
            return;
        }
        // 滞回的判据用<b>生效</b>等级：人工已经降到 3 时，积压该按恢复阈值判，
        // 否则自动逻辑会在人工降级期间反复得出"要升到 3"（虽然写进去也无害，
        // 但每轮一次 Redis 写 + 一条告警是噪音）
        int effective = degradeSwitch.getLevel();
        // "有没有变"的判据必须用<b>自动</b>那一半，见 DegradeSwitch.autoLevel 的注释：
        // 用生效等级判断时，人工降到 4 而自动算出 0 会让这里每轮都认为"变了"
        int currentAuto = degradeSwitch.getAutoLevel();

        List<String> reasons = new ArrayList<>(2);
        int target = 0;

        Integer byBacklog = evalBacklog(effective, reasons);
        if (byBacklog != null) {
            target = Math.max(target, byBacklog);
        }
        Integer byDb = evalDbPool(effective, reasons);
        if (byDb != null) {
            target = Math.max(target, byDb);
        }

        if (target == currentAuto) {
            return;
        }
        String reason = reasons.isEmpty() ? "全部指标已恢复" : String.join("; ", reasons);
        degradeSwitch.setAutoLevel(target, reason);
        // 升级和降级都告警。只在升级时告警的话，"什么时候恢复的"要去翻日志，
        // 而故障复盘里这个时间点和降级时间点一样重要
        alarm.p2(AlarmService.Event.DEGRADE_LEVEL, String.valueOf(target),
                "auto %d → %d: %s".formatted(currentAuto, target, reason));
        log.warn("stage=DEGRADE_MONITOR autoLevel {} → {} reason={}",
                currentAuto, target, reason);
    }

    /**
     * 消息积压 → Level 3（暂停新资格分配）。
     *
     * <p>为什么是 3 而不是 2：积压意味着<b>已排队的订单还没落库</b>。
     * 保住的优先级里"已排队订单落库"高于"新资格分配"，所以要停的正是入口。
     * 拉长轮询间隔（Level 2）在这里没用——问题不在结果接口的压力，
     * 而在消费端的吞吐。
     *
     * @return 该条件要求的等级；{@code null} 表示采不到数据（不参与取最大值）
     */
    private Integer evalBacklog(int currentLevel, List<String> reasons) {
        MqBacklogMonitor monitor = backlogMonitor.getIfAvailable();
        if (monitor == null) {
            return null;
        }
        Long lag = monitor.collect();
        if (lag == null) {
            // 采不到（broker 不可达、消费组还没建立）。<b>不能当成 0</b>：
            // broker 挂了正是最该降级的时候，判 0 会解除降级把流量全放进来。
            // 返回 null 让这一条不参与计算，保持当前等级
            log.debug("stage=DEGRADE_MONITOR 积压采集失败，本条不参与判定");
            return null;
        }

        long threshold = props.getDegrade().getBacklogThreshold();
        long recover = (long) (threshold * props.getDegrade().getRecoverRatio());
        // 滞回：已经在降级中时用恢复阈值（更低），没降级时用触发阈值
        long effective = currentLevel >= 3 ? recover : threshold;

        trackStall(lag);
        if (lag > effective) {
            reasons.add("消息积压 " + lag + " 超过阈值 " + effective);
            alarm.p2(AlarmService.Event.MQ_BACKLOG, "FSS_ORDER_CREATE",
                    "积压 %d 超阈值 %d".formatted(lag, effective));
            return 3;
        }
        if (stalledRounds >= props.getDegrade().getStalledRounds()) {
            reasons.add("消费端连续 " + stalledRounds + " 轮无进展且仍有积压 " + lag);
            return 3;
        }
        return 0;
    }

    /**
     * 消费端停滞判定：积压 > 0 且与上一轮<b>完全没有减少</b>。
     *
     * <p>用"没有减少"而不是"TPS = 0"：TPS 需要采样消费速率，而积压本身的变化量
     * 已经蕴含了这个信息，且不受生产侧速率干扰——生产和消费一样快时积压持平，
     * 那也确实是"消费端跟不上新增"，同样值得降级。
     */
    private void trackStall(long lag) {
        if (lag > 0 && lastBacklog >= 0 && lag >= lastBacklog) {
            stalledRounds++;
        } else {
            stalledRounds = 0;
        }
        lastBacklog = lag;
    }

    /**
     * 连接池等待 → Level 2（拉长客户端轮询间隔）。
     *
     * <p>为什么是 2：连接池被打满时最先该卸掉的是<b>最廉价但量最大</b>的那部分负载，
     * 也就是结果轮询——1 万个用户按 300ms 轮询就是 33000 QPS，每一次都要一次查询。
     * 拉到 2000ms 直接把它降一个数量级，而秒杀入口和支付都不受影响。
     */
    private Integer evalDbPool(int currentLevel, List<String> reasons) {
        DataSource ds = dataSource.getIfAvailable();
        if (!(ds instanceof HikariDataSource hikari)) {
            return null;
        }
        try {
            int pending = hikari.getHikariPoolMXBean().getThreadsAwaitingConnection();
            int threshold = props.getDegrade().getDbPendingThreshold();
            int effective = currentLevel >= 2
                    ? (int) Math.max(1, threshold * props.getDegrade().getRecoverRatio())
                    : threshold;
            if (pending > effective) {
                reasons.add("连接池等待线程 " + pending + " 超过阈值 " + effective);
                return 2;
            }
            return 0;
        } catch (Exception e) {
            // 池还没初始化完或已关闭。同样返回 null 而不是 0
            log.debug("stage=DEGRADE_MONITOR 连接池指标读取失败", e);
            return null;
        }
    }
}

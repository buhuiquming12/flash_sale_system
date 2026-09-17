package com.fss.infra.alarm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 告警出口。
 *
 * <p><b>为什么要有这个类，而不是各处直接 {@code log.error}</b>：阶段一到三的代码里
 * "需要人介入"的场合写的是 {@code log.error("...需人工处理")}，那意味着告警的
 * 触发条件散落在几十处日志文本里。真接上告警系统时没人能回答"我们一共会发多少种告警"。
 *
 * <p>这里做三件事，每件都是为了让告警可运维：
 * <ol>
 *   <li><b>固定日志格式</b> {@code stage=ALARM severity=P1 event=... key=...}，
 *       Loki 一条查询就能拉出全部告警：{@code {app="fss"} |= "stage=ALARM" | logfmt}</li>
 *   <li><b>同时打指标</b> {@code fss_alarm_total{severity, event}}。日志告警依赖日志
 *       管道，而日志管道本身也会挂；指标这条路是独立的，且能做"5 分钟内 P1 增量 > 0"
 *       这类规则</li>
 *   <li><b>吞掉自身异常</b>。告警发不出去绝不能把业务链路带崩——最需要告警的时刻
 *       往往正是依赖不稳的时刻</li>
 * </ol>
 *
 * <p><b>不做重复抑制。</b> 同一事件每秒触发一千次就打一千条，看起来像缺陷，但抑制
 * 需要状态（内存里的话多实例各抑制一份、重启即丢；Redis 里的话告警链路又多一个依赖，
 * 而 Redis 挂掉正是要告警的场景）。收敛留给告警系统做——Prometheus 的
 * {@code for:} 与 Alertmanager 的 {@code group_interval} 就是干这个的，
 * 应用侧硬做一遍只会让"为什么这条告警没发出来"多一个排查层。
 *
 * <p>本项目没有真实的短信/电话通道（{@code P1} 只是日志与指标里的一个标签）。
 * 这是演示环境的取舍，但触发点、分级、事件名都是真的，接通道只需要在这里加一个
 * HTTP 调用。
 */
@Slf4j
@Component
public class AlarmService {

    private final MeterRegistry registry;

    public AlarmService(MeterRegistry registry) {
        this.registry = registry;
    }

    /** P1：不变量被违反或有资损风险，立刻要人。库存为负、Redis > DB、支付对账差异 */
    public void p1(String event, String key, String detail) {
        send(Severity.P1, event, key, detail);
    }

    /** P2：需要人看但不必立刻，工作时间处理。积压、死信、对账差异、投递放弃 */
    public void p2(String event, String key, String detail) {
        send(Severity.P2, event, key, detail);
    }

    private void send(Severity severity, String event, String key, String detail) {
        try {
            if (severity == Severity.P1) {
                log.error("stage=ALARM severity={} event={} key={} detail={}",
                        severity, event, key, detail);
            } else {
                log.warn("stage=ALARM severity={} event={} key={} detail={}",
                        severity, event, key, detail);
            }
            Counter.builder("fss_alarm_total")
                    .tag("severity", severity.name())
                    // event 是有限集合（下面的常量），可以当标签；
                    // key 是 orderNo / requestNo 这类高基数值，只进日志不进标签
                    .tag("event", event)
                    .register(registry)
                    .increment();
        } catch (Exception e) {
            log.error("告警发送失败 event={}", event, e);
        }
    }

    private enum Severity {
        P1, P2
    }

    /**
     * 事件名常量。
     *
     * <p>必须是<b>有限的枚举集合</b>：它进了 Prometheus 标签，动态拼接
     * （比如把 orderNo 拼进 event）会让时间序列爆炸。
     */
    public static final class Event {
        /** Redis 库存大于 DB 可售库存，存在超卖风险 */
        public static final String STOCK_REDIS_GT_DB   = "STOCK_REDIS_GT_DB";
        /** 库存对账差异，无法自动修正 */
        public static final String STOCK_DRIFT         = "STOCK_DRIFT";
        /** 库存账目内部不自洽（total ≠ available + locked + sold） */
        public static final String STOCK_IDENTITY      = "STOCK_IDENTITY_BROKEN";
        /** 资格对账发现孤儿：Redis 说排队中，DB 里没有订单也没有消息 */
        public static final String ORPHAN_QUALIFICATION = "ORPHAN_QUALIFICATION";
        /** 支付对账差异 */
        public static final String PAYMENT_DIFF        = "PAYMENT_DIFF";
        /** 支付与关单竞态：钱收到了但订单已关闭 */
        public static final String PAY_CANCEL_RACE     = "PAY_CANCEL_RACE";
        /** 消息投递彻底失败，已放弃 */
        public static final String MQ_GIVE_UP          = "MQ_GIVE_UP";
        /** 消息进入死信队列 */
        public static final String MQ_DLQ              = "MQ_DLQ";
        /** 消息积压超阈值 */
        public static final String MQ_BACKLOG          = "MQ_BACKLOG";
        /** 本地消息表显示已发送，但 broker 按 key 查不到消息 */
        public static final String MQ_MESSAGE_LOST     = "MQ_MESSAGE_LOST";
        /** 自动降级等级变化 */
        public static final String DEGRADE_LEVEL       = "DEGRADE_LEVEL_CHANGED";
        /** Redis 调用结果不确定 */
        public static final String REDIS_UNCERTAIN     = "REDIS_UNCERTAIN";
        /** 活动预热失败 */
        public static final String WARMUP_FAILED       = "WARMUP_FAILED";
        /** 对账任务自身执行失败 */
        public static final String RECONCILE_FAILED    = "RECONCILE_JOB_FAILED";

        private Event() {
        }
    }
}

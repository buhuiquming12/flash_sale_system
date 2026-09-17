package com.fss.infra.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 业务指标出口。所有自定义指标都从这里注册，一处也不散落。
 *
 * <h3>三条规则，违反任何一条都会在生产上出事</h3>
 * <ol>
 *   <li><b>标签基数必须有界。</b> {@code activityId} / {@code skuId} 可以做标签
 *       （活动数量有限），{@code userId} / {@code requestNo} / {@code orderNo}
 *       绝对不行——每个值都是一条独立时间序列，1 万个用户就是 1 万条，
 *       Prometheus 会先 OOM 再拖垮整个监控。高基数的值只进日志。</li>
 *   <li><b>失败原因用标签而不是独立指标。</b> {@code fss_seckill_request_total{result="stock_out"}}
 *       而不是 {@code fss_seckill_stock_out_total}。标签维度可以在 Grafana 里任意
 *       聚合与下钻，独立 Counter 不行——想画"总失败率"就得把所有指标名列一遍，
 *       新加一种失败原因还要改看板。</li>
 *   <li><b>仪表用推的，不用采样回调。</b> 见 {@link #publishStockGauges}。</li>
 * </ol>
 *
 * <h3>指标名不能带 Prometheus 的保留后缀</h3>
 * 这一条是联调时才发现的，而且<b>测试抓不到</b>：Micrometer 里的名字和导出到
 * {@code /actuator/prometheus} 的名字不是同一个东西。prometheus-metrics-core 1.x 会
 * 剥掉保留后缀（{@code _total} / {@code _created} / {@code _sum} / {@code _count} /
 * {@code _bucket} / {@code _info}），Counter 再自己补一个 {@code _total} 回去。于是：
 * <pre>
 * fss_order_created_total  →  剥 _total → fss_order_created → 剥 _created → fss_order → fss_order_total
 * fss_stock_total（Gauge） →  剥 _total → fss_stock
 * </pre>
 * 两个名字在导出侧被悄悄改掉，而 {@code registry.find("fss_order_created_total")}
 * 在<b>注册侧</b>照样找得到——所以单测全绿，告警规则和看板却在查一个不存在的序列。
 * 现在这两个指标改名为 {@link #orderCreated}({@code fss_order_create_total}) 与
 * {@code fss_stock_capacity}，并由 {@code MetricsExportTest} 直接断言导出文本。
 *
 * <p><b>为什么不做成 AOP 切面</b>（设计文档 docs/07 §6 给的是 {@code @Metered} 注解 +
 * 切面）：切面只能拿到方法的入参与返回值，而这里要打的标签是
 * {@code activityId / skuId / result}，得从 {@code SeckillCmd} 里反射掏，
 * 或者约定"第一个参数必须是 cmd"。约定一旦被下一个人破坏，编译期没有任何提示，
 * 埋点静默失效——而埋点失效是不会有人发现的，直到某天要看数据。
 * 显式调用多写一行，但调用点就在被埋的代码旁边，改代码时看得见。
 */
@Slf4j
@Component
public class SeckillMetrics {

    private final MeterRegistry registry;

    /** 库存三视图：Redis 剩余 / DB 可售 / 排队中占用 / 总库存。由对账任务整体替换 */
    private final MultiGauge stockRemain;
    private final MultiGauge stockDbAvailable;
    private final MultiGauge stockQueueing;
    private final MultiGauge stockTotal;
    /** 消息积压。由积压监控整体替换 */
    private final MultiGauge mqBacklog;

    public SeckillMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.stockRemain      = MultiGauge.builder("fss_stock_remain")
                .description("Redis 侧剩余可抢库存").register(registry);
        this.stockDbAvailable = MultiGauge.builder("fss_stock_db_available")
                .description("DB 侧可售库存").register(registry);
        this.stockQueueing    = MultiGauge.builder("fss_stock_queueing")
                .description("已预扣但尚未落库的排队中数量").register(registry);
        this.stockTotal       = MultiGauge.builder("fss_stock_capacity")
                .description("活动总库存").register(registry);
        this.mqBacklog        = MultiGauge.builder("fss_mq_backlog")
                .description("消费组积压量 = maxOffset - consumerOffset").register(registry);
    }

    // ==================================================================
    // 计数器
    // ==================================================================

    /**
     * 秒杀请求。{@code result} 取 {@code qualified} 或错误码的小写名
     * （{@code stock_not_enough} / {@code already_bought} / {@code rate_limited} / ...）。
     */
    public void seckillRequest(long activityId, long skuId, String result) {
        counter("fss_seckill_request_total", activityId, skuId, "result", result);
    }

    /** 资格分配成功（Lua 预扣成功）。与 request_total{result="qualified"} 冗余是故意的：
     *  它是最重要的一条曲线，独立指标让告警规则不依赖标签选择器写对 */
    public void qualified(long activityId, long skuId) {
        counter("fss_seckill_qualified_total", activityId, skuId);
    }

    /**
     * 订单落库成功。
     *
     * <p>名字是 {@code fss_order_create_total} 而不是更自然的
     * {@code fss_order_created_total}：后者导出时会被剥成 {@code fss_order_total}
     * （先剥 {@code _total}、再剥保留后缀 {@code _created}），
     * 而 {@code registry.find} 在注册侧照样找得到——测试全绿、看板空白。见类注释。
     */
    public void orderCreated(long activityId, long skuId) {
        counter("fss_order_create_total", activityId, skuId);
    }

    /** {@code reason} 用错误码小写名 */
    public void orderCreateFailed(long activityId, long skuId, String reason) {
        counter("fss_order_create_failed_total", activityId, skuId, "reason", reason);
    }

    public void orderPaid(long activityId, long skuId) {
        counter("fss_order_paid_total", activityId, skuId);
    }

    /** {@code reason}: timeout / user / system */
    public void orderCancelled(long activityId, long skuId, String reason) {
        counter("fss_order_cancelled_total", activityId, skuId, "reason", reason);
    }

    /** {@code type}: release（取消回补）/ compensate（补偿回补） */
    public void stockRollback(long activityId, long skuId, String type) {
        counter("fss_stock_rollback_total", activityId, skuId, "type", type);
    }

    /** {@code type}: qualification / stock / payment；{@code action}: auto_fixed / need_manual */
    public void reconcileDiff(String type, String action) {
        Counter.builder("fss_reconcile_diff_total")
                .tag("type", type).tag("action", action)
                .register(registry).increment();
    }

    public void mqResend(String topic) {
        Counter.builder("fss_mq_resend_total").tag("topic", topic)
                .register(registry).increment();
    }

    public void mqGiveUp(String topic) {
        Counter.builder("fss_mq_give_up_total").tag("topic", topic)
                .register(registry).increment();
    }

    public void dlq(String topic) {
        Counter.builder("fss_dlq_total").tag("topic", topic)
                .register(registry).increment();
    }

    /** 本地消息登记到首次确认消费的端到端时延。 */
    public void mqDeliveryLatency(String topic, long nanos) {
        Timer.builder("fss_mq_delivery_seconds")
                .tag("topic", topic)
                .description("本地消息登记到消费确认的端到端时延")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofHours(1))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** Redis 调用结果不确定。这条曲线抬头意味着 Redis 在超时边缘 */
    public void redisUncertain(String stage) {
        Counter.builder("fss_redis_uncertain_total").tag("stage", stage)
                .register(registry).increment();
    }

    /** 定时任务执行失败。告警规则 {@code ReconcileJobFailing} 读它 */
    public void jobError(String job) {
        Counter.builder("fss_job_error_total").tag("job", job)
                .register(registry).increment();
    }

    // ==================================================================
    // 计时器
    // ==================================================================

    /**
     * Lua 脚本耗时。
     *
     * <p>单独埋点的理由：Lua 是整个系统唯一的串行瓶颈（Redis 单线程执行脚本），
     * 它的 P99 直接决定秒杀接口的 P99。接口耗时上去时，这条曲线能立刻回答
     * "是 Redis 慢了，还是应用侧慢了"——没有它就只能靠猜，或者去翻 Redis 的
     * slowlog（而 slowlog 的默认阈值是 10ms，5ms 的劣化根本不会被记下来）。
     *
     * <h3>必须是 {@code publishPercentileHistogram} 而不是 {@code publishPercentiles}</h3>
     * 两个方法名字很像，导出的东西完全不同：
     * <ul>
     *   <li>{@code publishPercentiles(0.99)} 在<b>应用内</b>算好分位数，导出成
     *       {@code fss_lua_execution_seconds{quantile="0.99"}}。它是单实例的、
     *       不可聚合的——两个实例的 P99 没法合成集群 P99（分位数不能求平均）。</li>
     *   <li>{@code publishPercentileHistogram()} 导出 {@code _bucket} 系列，
     *       由 Prometheus 侧用 {@code histogram_quantile()} 计算。桶是可加的，
     *       所以能跨实例聚合，也能任意改时间窗口。</li>
     * </ul>
     * 告警规则 {@code LuaSlow} 和看板都用 {@code histogram_quantile(...
     * rate(fss_lua_execution_seconds_bucket[5m]))}，配成前者时那个查询<b>返回空</b>，
     * 而 Prometheus 对"查不到序列"的表达式既不报错也不告警——规则静默失效。
     *
     * <p>{@code minimumExpectedValue} / {@code maximumExpectedValue} 限定桶的范围：
     * 不限定时 Micrometer 会生成覆盖 1ns~30s 的一大堆桶，而 Lua 耗时的合理区间是
     * 毫秒到几百毫秒——多出来的桶纯粹是时间序列浪费。下限取 1ms 而不是更细：
     * 告警阈值是 P99 &gt; 5ms，比 1ms 更细的分辨率对这个判断没有价值。
     */
    public void luaTimer(String script, long nanos) {
        Timer.builder("fss_lua_execution_seconds")
                .tag("script", script)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(2))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    // ==================================================================
    // 仪表
    // ==================================================================

    /**
     * 注册降级等级仪表。用回调采样是<b>安全的</b>，因为它读的是一个本地
     * volatile int，不涉及任何 IO —— 这正是与库存仪表的区别所在。
     */
    public void registerDegradeLevel(Supplier<Number> level) {
        Gauge.builder("fss_degrade_level", level)
                .description("当前降级等级 0~4")
                .strongReference(true)
                .register(registry);
    }

    /**
     * 上报一组库存视图。
     *
     * <h3>为什么用推送而不是 {@code Gauge.builder(name, () -> readRedis())}</h3>
     * 回调式仪表在<b>每次 Prometheus 抓取时</b>执行。1 个活动 10 个 SKU、
     * 4 个库存指标，一次抓取就是 40 次 Redis/DB 查询；抓取间隔 15 秒的话，
     * 监控本身成了一个稳定的负载源。更糟的是失败模式：Redis 变慢 → 抓取超时 →
     * <b>整个 /actuator/prometheus 端点失败</b> → 连"Redis 慢了"这件事都看不到了，
     * 因为报告它的那条曲线也断了。
     *
     * <p>推送式则是：对账任务本来就要读这些值（它就是干这个的），顺手写进内存；
     * 抓取只是读内存。代价是数值最多滞后一个对账周期（5 分钟），
     * 对"库存是否漂移"这种问题完全够——它本来就不是秒级指标。
     *
     * <p><b>用 {@link MultiGauge} 而不是自己维护一个 Map</b>：活动会不断新增，
     * 结束的活动如果不清理，时间序列只增不减。MultiGauge 的 {@code register}
     * 会整体替换——这一轮没有的行自动消失，正好对上"活动结束就不再上报"的语义。
     */
    public void publishStockGauges(List<StockView> views) {
        try {
            stockRemain.register(rows(views, StockView::redisStock), true);
            stockDbAvailable.register(rows(views, StockView::dbAvailable), true);
            stockQueueing.register(rows(views, StockView::queueing), true);
            stockTotal.register(rows(views, StockView::total), true);
        } catch (Exception e) {
            log.warn("库存仪表上报失败", e);
        }
    }

    /**
     * {@code MultiGauge.register} 收的是 {@code Iterable<Row<?>>}，
     * 而 {@code stream().map(Row::of).toList()} 推导出来的是 {@code List<Row<Number>>}——
     * 泛型不协变，直接传会编译不过。抽成这个方法把通配符收在一处，
     * 比在四个调用点各写一次显式类型参数干净。
     */
    private static List<MultiGauge.Row<?>> rows(List<StockView> views,
                                                java.util.function.ToDoubleFunction<StockView> f) {
        List<MultiGauge.Row<?>> out = new java.util.ArrayList<>(views.size());
        for (StockView v : views) {
            out.add(MultiGauge.Row.of(tagsOf(v), f.applyAsDouble(v)));
        }
        return out;
    }

    public void publishBacklogGauges(List<BacklogView> views) {
        try {
            List<MultiGauge.Row<?>> out = new java.util.ArrayList<>(views.size());
            for (BacklogView v : views) {
                out.add(MultiGauge.Row.of(
                        Tags.of("topic", v.topic(), "group", v.group()), v.lag()));
            }
            mqBacklog.register(out, true);
        } catch (Exception e) {
            log.warn("积压仪表上报失败", e);
        }
    }

    /**
     * 单个 Topic 的积压量。给"暂时只知道一个 Topic"的调用方用，
     * 内部转成一行推给 {@link #publishBacklogGauges}——不额外开一个指标名。
     */
    public void publishBacklog(String topic, String group, long lag) {
        publishBacklogGauges(List.of(new BacklogView(topic, group, lag)));
    }

    private static Tags tagsOf(StockView v) {
        return Tags.of("activity", String.valueOf(v.activityId()),
                "sku", String.valueOf(v.skuId()));
    }

    private void counter(String name, long activityId, long skuId, String... extraTags) {
        Counter.Builder b = Counter.builder(name)
                .tag("activity", String.valueOf(activityId))
                .tag("sku", String.valueOf(skuId));
        for (int i = 0; i + 1 < extraTags.length; i += 2) {
            b = b.tag(extraTags[i], extraTags[i + 1]);
        }
        b.register(registry).increment();
    }

    /** 一个秒杀商品的四个库存视图。{@code redisStock} 未预热时传 -1，画在图上一眼能看出 */
    public record StockView(long activityId, long skuId,
                            long redisStock, long dbAvailable,
                            long queueing, long total) {
    }

    public record BacklogView(String topic, String group, long lag) {
    }

    /** 供测试断言"这个计数器确实动了"，避免测试去解析 /actuator/prometheus 文本 */
    public double counterValue(String name, String... tags) {
        Counter c = registry.find(name).tags(tags).counter();
        return c == null ? -1 : c.count();
    }

    public double gaugeValue(String name, String... tags) {
        Gauge g = registry.find(name).tags(tags).gauge();
        return g == null ? -1 : g.value();
    }
}

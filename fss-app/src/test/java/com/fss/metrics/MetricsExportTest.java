package com.fss.metrics;

import com.fss.infra.alarm.AlarmService;
import com.fss.infra.metrics.SeckillMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标<b>导出文本</b>验收。
 *
 * <h3>为什么必须单独有这个类</h3>
 * Micrometer 里注册的名字和导出到 {@code /actuator/prometheus} 的名字<b>不是同一个</b>。
 * prometheus-metrics-core 1.x 会剥掉保留后缀（{@code _total} / {@code _created} /
 * {@code _sum} / {@code _count} / {@code _bucket} / {@code _info}），Counter 再补一个
 * {@code _total} 回去。联调时踩到的两个真实例子：
 * <pre>
 * fss_order_created_total  →  fss_order_total     （先剥 _total，再剥 _created）
 * fss_stock_total（Gauge） →  fss_stock           （剥 _total，Gauge 不补回去）
 * </pre>
 * 而 {@link MetricsTest} 用的 {@code registry.find("fss_order_created_total")} 是在
 * <b>注册侧</b>查的，照样命中——所以那套断言全绿，告警规则和 Grafana 看板却在查
 * 一个根本不存在的序列。<b>而 Prometheus 对"查不到序列"既不报错也不告警</b>，
 * 规则就这么静默失效了。
 *
 * <p>同类的第三个坑是 {@code publishPercentiles} 与 {@code publishPercentileHistogram}：
 * 前者导出 {@code {quantile="0.99"}}（应用内算好，不可跨实例聚合），
 * 后者导出 {@code _bucket}（Prometheus 侧用 {@code histogram_quantile} 算）。
 * 告警规则用的是后者的形式，配成前者时查询返回空。
 *
 * <h3>为什么不继承 IntegrationTestBase</h3>
 * "指标名怎么被翻译成导出文本"是 exposition format 的性质，与 Spring、
 * 与数据库、与 Redis 都无关。直接 new 一个 {@link PrometheusMeterRegistry}
 * 就能覆盖，省掉三个容器和一个 Spring 上下文——这个用例因此是毫秒级的，
 * 而它要防的正是那种"改个指标名就静默失效"的低成本失误，跑得快才会被真的跑。
 */
class MetricsExportTest {

    /** 告警规则文件相对模块目录的位置。与 application-test.yml 引用 sql 的方式一致 */
    private static final Path ALERT_RULES = Path.of("../docker/prometheus/alert-rules.yml");

    /** 从 PromQL 里抓 fss_ 开头的指标名 */
    private static final Pattern FSS_METRIC = Pattern.compile("\\bfss_[a-z0-9_]+\\b");

    private PrometheusMeterRegistry registry;
    private SeckillMetrics          metrics;
    private AlarmService            alarm;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new SeckillMetrics(registry);
        // fss_alarm_total 由 AlarmService 注册，不在 SeckillMetrics 里。
        // 少了这一行，E4 会以"告警规则引用了不存在的指标"失败——而那正是
        // 这个用例想抓的那类问题，只不过成因是测试自己没覆盖全告警出口
        alarm = new AlarmService(registry);
    }

    /** 把全部指标各打一次，让惰性注册的都出现在导出文本里 */
    private String scrapeAll() {
        long a = 1L;
        long s = 2L;
        metrics.seckillRequest(a, s, "qualified");
        metrics.qualified(a, s);
        metrics.orderCreated(a, s);
        metrics.orderCreateFailed(a, s, "stock_not_enough");
        metrics.orderPaid(a, s);
        metrics.orderCancelled(a, s, "timeout");
        metrics.stockRollback(a, s, "release");
        metrics.reconcileDiff("stock", "auto_fixed");
        metrics.mqResend("FSS_ORDER_CREATE");
        metrics.mqGiveUp("FSS_ORDER_CREATE");
        metrics.dlq("FSS_ORDER_CREATE");
        metrics.mqDeliveryLatency("FSS_ORDER_CREATE", TimeUnit.MILLISECONDS.toNanos(25));
        metrics.redisUncertain("seckill");
        metrics.jobError("reconcile-stock");
        metrics.luaTimer("seckill", TimeUnit.MILLISECONDS.toNanos(3));
        metrics.registerDegradeLevel(() -> 0);
        metrics.publishStockGauges(List.of(
                new SeckillMetrics.StockView(a, s, 9, 9, 0, 10)));
        metrics.publishBacklog("FSS_ORDER_CREATE", "GID_FSS_ORDER_CREATE", 0);
        alarm.p1(AlarmService.Event.STOCK_REDIS_GT_DB, "1:2", "测试");
        alarm.p2(AlarmService.Event.MQ_BACKLOG, "FSS_ORDER_CREATE", "测试");
        return registry.scrape();
    }

    @Test
    @DisplayName("E1 Counter 的导出名 = 剥掉保留后缀再补 _total")
    void 计数器导出名() {
        String text = scrapeAll();

        assertThat(text)
                .as("叫 fss_order_created_total 的话会被剥成 fss_order_total —— "
                        + "先剥 _total，再剥保留后缀 _created，Counter 再补一个 _total 回来")
                .contains("fss_order_create_total")
                .doesNotContain("fss_order_total");

        assertThat(text)
                .contains("fss_seckill_request_total")
                .contains("fss_seckill_qualified_total")
                .contains("fss_order_create_failed_total")
                .contains("fss_order_paid_total")
                .contains("fss_order_cancelled_total")
                .contains("fss_stock_rollback_total")
                .contains("fss_reconcile_diff_total")
                .contains("fss_mq_resend_total")
                .contains("fss_mq_give_up_total")
                .contains("fss_dlq_total")
                .contains("fss_redis_uncertain_total")
                .contains("fss_job_error_total");
    }

    @Test
    @DisplayName("E2 Gauge 不会被补回 _total，所以总库存必须叫 fss_stock_capacity")
    void 仪表导出名() {
        String text = scrapeAll();

        assertThat(text)
                .as("叫 fss_stock_total 时导出成 fss_stock，而 OrderExceedsTotalStock "
                        + "这条 P1 规则会永远查不到右侧的序列 —— 于是永远不触发")
                .contains("fss_stock_capacity")
                .doesNotContain("fss_stock_total");

        assertThat(text)
                .contains("fss_stock_remain")
                .contains("fss_stock_db_available")
                .contains("fss_stock_queueing")
                .contains("fss_mq_backlog")
                .contains("fss_degrade_level");
    }

    @Test
    @DisplayName("E3 Lua 计时器必须导出 _bucket 系列，否则 histogram_quantile 查不到东西")
    void Lua导出直方图桶() {
        String text = scrapeAll();

        assertThat(text)
                .as("告警规则 LuaSlow 与看板都用 histogram_quantile(... "
                        + "rate(fss_lua_execution_seconds_bucket[5m]))。"
                        + "配成 publishPercentiles 时导出的是 {quantile=...}，"
                        + "那个查询返回空 —— 而 Prometheus 不会因为查不到序列而报错")
                .contains("fss_lua_execution_seconds_bucket")
                .contains("script=\"seckill\"");
    }

    @Test
    @DisplayName("E4 告警规则里引用的每个 fss_ 指标都真的会被导出")
    void 告警规则引用的指标都存在() throws IOException {
        String rules = Files.readString(ALERT_RULES);
        String exported = scrapeAll();

        List<String> missing = new ArrayList<>();
        Matcher m = FSS_METRIC.matcher(rules);
        while (m.find()) {
            String name = m.group();
            if (!exported.contains(name)) {
                missing.add(name);
            }
        }

        assertThat(missing)
                .as("告警规则引用了导出里不存在的指标名。这类失配不会有任何报错——"
                        + "Prometheus 对查不到序列的表达式既不报错也不告警，规则静默失效。"
                        + "改指标名时改了代码忘了改规则，正是这个用例要抓的")
                .isEmpty();
    }

    @Test
    @DisplayName("E5 Grafana 看板引用的每个 fss_ 指标也都存在")
    void 看板引用的指标都存在() throws IOException {
        String dashboard = Files.readString(
                Path.of("../docker/grafana/provisioning/dashboards/fss-overview.json"));
        String exported = scrapeAll();

        List<String> missing = new ArrayList<>();
        Matcher m = FSS_METRIC.matcher(dashboard);
        while (m.find()) {
            if (!exported.contains(m.group())) {
                missing.add(m.group());
            }
        }
        assertThat(missing)
                .as("看板上的空图比没有图更糟：它让人以为"
                        + "「这项指标是 0」而不是「这项指标不存在」")
                .isEmpty();
    }
}

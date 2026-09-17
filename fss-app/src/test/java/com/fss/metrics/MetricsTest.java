package com.fss.metrics;

import com.fss.biz.consumer.OrderCreateListener;
import com.fss.biz.seckill.model.SeckillSubmitVO;
import com.fss.common.error.BizException;
import com.fss.common.util.JsonUtil;
import com.fss.domain.message.OrderCreateMessage;
import com.fss.infra.metrics.SeckillMetrics;
import com.fss.test.IntegrationTestBase;
import com.fss.test.TestFixture;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 监控埋点验收（阶段四）。
 *
 * <p>这个类守的不是"指标值对不对"——那些断言分散在各自的业务用例里（库存仪表在
 * {@code ReconcileTest}、降级等级在 {@code DegradeTest}）。它守的是两条<b>会静默失效</b>
 * 的不变量：
 * <ol>
 *   <li><b>关键指标存在。</b> 埋点被删掉、改名、或者因为重构走进了另一个分支时，
 *       没有任何编译错误、没有任何测试失败——直到某天要看数据才发现曲线是空的。</li>
 *   <li><b>标签基数有界。</b> 有人把 {@code userId} 或 {@code requestNo} 加成标签时，
 *       本地跑几十个请求完全正常，上线后 Prometheus 会先 OOM 再拖垮整个监控。
 *       这是最贵的一类失误，而它在代码 review 里长得像"多加了一个有用的维度"。</li>
 * </ol>
 */
class MetricsTest extends IntegrationTestBase {

    /** 禁止出现在任何自定义指标标签里的高基数键 */
    private static final Set<String> FORBIDDEN_TAGS =
            Set.of("userId", "user_id", "requestNo", "request_no",
                    "orderNo", "order_no", "payNo", "pay_no", "traceId", "trace_id");

    @Autowired SeckillMetrics      metrics;
    @Autowired MeterRegistry       registry;
    @Autowired TestFixture         fixture;
    @Autowired OrderCreateListener orderCreateListener;

    @Test
    @DisplayName("M1 秒杀主链路的关键指标都在")
    void 关键指标存在() {
        TestFixture.Activity act = fixture.createRunningActivity(2);
        long a = act.activityId();
        long s = act.skuId();

        long u1 = fixture.createUser();
        fixture.submitAndAwait(act, u1);
        // 同一用户重抢 → 一人一单拒绝，走 request_total{result=already_bought}
        assertThatThrownBy(() -> fixture.submitAndAwait(act, u1))
                .isInstanceOf(BizException.class);

        String[] tags = {"activity", String.valueOf(a), "sku", String.valueOf(s)};
        assertThat(metrics.counterValue("fss_seckill_request_total",
                "activity", String.valueOf(a), "sku", String.valueOf(s),
                "result", "qualified")).isEqualTo(1.0);
        assertThat(metrics.counterValue("fss_seckill_request_total",
                "activity", String.valueOf(a), "sku", String.valueOf(s),
                "result", "already_bought")).isEqualTo(1.0);
        assertThat(metrics.counterValue("fss_seckill_qualified_total", tags)).isEqualTo(1.0);
        // 全量测试会缓存多个 Spring 上下文，它们使用同一消费组，等价于多个应用实例。
        // 经 broker 投递的消息可能被另一个上下文消费，不能据此断言当前实例的 Registry。
        // 直接让当前上下文的真实 Listener 处理一条独立消息，确定性验证消费埋点调用链。
        String requestNo = "R_METRICS_" + System.nanoTime();
        OrderCreateMessage message = OrderCreateMessage.builder()
                .requestNo(requestNo)
                .userId(fixture.createUser())
                .activityId(a)
                .skuId(s)
                .quantity(1)
                .requestTime(LocalDateTime.now())
                .traceId("trace-metrics")
                .version(OrderCreateMessage.CURRENT_VERSION)
                .build();
        MessageExt ext = new MessageExt();
        ext.setKeys(requestNo);
        ext.setBody(JsonUtil.toJson(message).getBytes(StandardCharsets.UTF_8));
        orderCreateListener.onMessage(ext);

        assertThat(registry.find("fss_order_create_total").counters())
                .as("订单创建计数器必须已注册。它是 OrderExceedsTotalStock "
                        + "这条 P1 告警规则的左侧，缺了规则永远不触发")
                .isNotEmpty();

        assertThat(registry.find("fss_lua_execution_seconds").tag("script", "seckill").timer())
                .as("Lua 是整个系统唯一的串行瓶颈，它的 P99 直接决定接口 P99。"
                        + "没有这条曲线，接口变慢时只能猜是 Redis 还是应用")
                .isNotNull();
    }

    @Test
    @DisplayName("M2 失败原因用标签而不是独立指标名")
    void 失败原因用标签() {
        TestFixture.Activity act = fixture.createRunningActivity(1);
        long a = act.activityId();
        long s = act.skuId();

        fixture.submitAndAwait(act, fixture.createUser());
        // 库存已耗尽
        assertThatThrownBy(() -> fixture.submitAndAwait(act, fixture.createUser()))
                .isInstanceOf(BizException.class);

        // 售罄之后 Lua 走的是 status=2 的快速失败分支，返回码是"库存不足"
        double stockOut = metrics.counterValue("fss_seckill_request_total",
                "activity", String.valueOf(a), "sku", String.valueOf(s),
                "result", "stock_not_enough");
        assertThat(stockOut)
                .as("同一个指标名 + result 标签，Grafana 里才能任意聚合与下钻。"
                        + "每种失败一个独立 Counter 的话，画「总失败率」要把指标名列一遍，"
                        + "新增一种失败原因还得改看板")
                .isEqualTo(1.0);

        assertThat(registry.find("fss_seckill_stock_not_enough_total").counter())
                .as("不该有按失败原因命名的独立指标")
                .isNull();
    }

    @Test
    @DisplayName("M3 自定义指标绝不能带 userId / requestNo 这类高基数标签")
    void 标签基数有界() {
        TestFixture.Activity act = fixture.createRunningActivity(3);
        // 造多个用户，如果哪里把 userId 当了标签，序列数会随用户数线性增长
        for (long uid : fixture.createUsers(3)) {
            SeckillSubmitVO vo = fixture.submitAndAwait(act, uid);
            assertThat(vo.getOrderNo()).isNotNull();
        }

        List<String> offenders = new java.util.ArrayList<>();
        registry.forEachMeter(m -> {
            if (!m.getId().getName().startsWith("fss_")) {
                return;                     // 只管自己的指标，不管框架自带的
            }
            m.getId().getTags().forEach(t -> {
                if (FORBIDDEN_TAGS.contains(t.getKey())) {
                    offenders.add(m.getId().getName() + "{" + t.getKey() + "}");
                }
            });
        });
        assertThat(offenders)
                .as("高基数标签会让 Prometheus 时间序列爆炸。这类值只进日志，"
                        + "靠 traceId 关联，不进指标")
                .isEmpty();

        // 三个用户各抢一单，但这个活动只该有一条序列。
        //
        // 必须按 activity 过滤：同一个 Spring 上下文里前面的用例各自建了活动，
        // 每个活动一条序列是<b>预期</b>的（activity 是有界维度）。
        // 不过滤就变成在断言"整个测试类只跑过一个活动"，那不是这里要守的不变量
        assertThat(seriesCountFor("fss_order_create_total", act.activityId()))
                .as("3 个用户下单后，该活动仍只有 1 条序列。变成 3 条就说明混进了用户维度")
                .isEqualTo(1);
    }

    /** 某指标在指定活动下的时间序列条数 */
    private int seriesCountFor(String name, long activityId) {
        int[] n = {0};
        registry.forEachMeter(m -> {
            if (m.getId().getName().equals(name)
                    && String.valueOf(activityId).equals(m.getId().getTag("activity"))) {
                n[0]++;
            }
        });
        return n[0];
    }

    @Test
    @DisplayName("M4 告警都从 AlarmService 出去，带 severity 与有限的 event 标签")
    void 告警指标形状() {
        // fss_alarm_total 是否有值取决于本轮跑过哪些用例，所以只验"形状"：
        // 一旦存在，标签必须是 severity + event 两个，且 event 是常量集合里的
        registry.forEachMeter(m -> {
            if (!"fss_alarm_total".equals(m.getId().getName())) {
                return;
            }
            assertThat(m.getId().getTag("severity")).isIn("P1", "P2");
            assertThat(m.getId().getTag("event"))
                    .as("event 必须来自 AlarmService.Event 的常量集合，"
                            + "拼进 orderNo 就变成高基数标签了")
                    .doesNotContain("-")
                    .matches("[A-Z_]+");
        });
    }

}

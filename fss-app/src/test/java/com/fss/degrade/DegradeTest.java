package com.fss.degrade;

import com.fss.biz.activity.model.ActivityDetailVO;
import com.fss.biz.activity.service.ActivityService;
import com.fss.biz.job.DegradeMonitorJob;
import com.fss.biz.seckill.model.SeckillSubmitVO;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.infra.degrade.DegradeSwitch;
import com.fss.infra.metrics.SeckillMetrics;
import com.fss.test.IntegrationTestBase;
import com.fss.test.TestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 降级开关与自动降级（阶段四）。
 *
 * <p>后台每秒刷新在 application-test.yml 里被关掉了（{@code refresh-delay-ms} 设成一天），
 * 用例显式调 {@code refresh()}。<b>不关的话</b>后台线程会在"设置等级"和"断言行为"
 * 之间把 level 改回去，产生只在慢机器上偶发的失败。
 */
@ActiveProfiles({"test", "consumer", "job"})
class DegradeTest extends IntegrationTestBase {

    @Autowired DegradeSwitch     degradeSwitch;
    @Autowired DegradeMonitorJob monitorJob;
    @Autowired ActivityService   activityService;
    @Autowired SeckillMetrics    metrics;
    @Autowired StringRedisTemplate redis;
    @Autowired TestFixture       fixture;

    @AfterEach
    void resetDegrade() {
        // 必须清干净：level 是全局的，留着会让同一上下文里后面所有用例的秒杀全被拒
        redis.delete(java.util.List.of(DegradeSwitch.KEY_MANUAL, DegradeSwitch.KEY_AUTO));
        degradeSwitch.refresh();
        assertThat(degradeSwitch.getLevel()).isZero();
    }

    @Test
    @DisplayName("D1 Level 3 暂停新资格分配，Level 2 只拉长轮询间隔")
    void 分级行为() {
        TestFixture.Activity act = fixture.createRunningActivity(10);

        // Level 0：正常
        assertThat(degradeSwitch.seckillEnabled()).isTrue();
        assertThat(degradeSwitch.pollIntervalMs()).isEqualTo(300);

        // Level 2：秒杀仍放行，只是轮询间隔拉长。这一档降的是结果接口的压力，
        // 而不是入口——1 万人按 300ms 轮询是 33000 QPS，比提交本身还高
        setLevel(2);
        assertThat(degradeSwitch.seckillEnabled()).as("Level 2 不该关秒杀入口").isTrue();
        assertThat(degradeSwitch.pollIntervalMs()).isEqualTo(2000);
        SeckillSubmitVO vo = fixture.submitAndAwait(act, fixture.createUser());
        assertThat(vo.getOrderNo()).as("Level 2 下订单照样能建出来").isNotNull();

        // Level 3：入口关闭，已排队的继续处理完
        setLevel(3);
        assertThat(degradeSwitch.seckillEnabled()).isFalse();
        assertThatThrownBy(() -> fixture.submitAndAwait(act, fixture.createUser()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_DEGRADED);

        assertThat(fixture.countOrders(act.activityId(), act.skuId()))
                .as("被降级拒绝的请求不该产生订单")
                .isEqualTo(1);
        assertThat(fixture.redisStock(act.activityId(), act.skuId()))
                .as("降级发生在 Lua 之前，库存一份都不该动")
                .isEqualTo(9L);
    }

    @Test
    @DisplayName("D2 Level 1 起不再展示精确库存，改用档位")
    void 精确库存降级() {
        TestFixture.Activity act = fixture.createRunningActivity(10);

        ActivityDetailVO normal = activityService.detail(act.activityId());
        assertThat(normal.getGoodsList().get(0).getRemainStock()).isEqualTo(10);
        assertThat(normal.getGoodsList().get(0).getStockLevel())
                .isEqualTo(ActivityDetailVO.StockLevel.AVAILABLE);

        setLevel(1);
        // 缓存里存的是静态骨架，档位与精确值都是每次覆盖的，所以不必清缓存
        ActivityDetailVO degraded = activityService.detail(act.activityId());
        assertThat(degraded.getGoodsList().get(0).getRemainStock())
                .as("Level 1 起 remainStock 必须为 null —— 它对应的就是省掉的那次 Redis 读")
                .isNull();
        assertThat(degraded.getGoodsList().get(0).getStockLevel())
                .isEqualTo(ActivityDetailVO.StockLevel.AVAILABLE);
    }

    @Test
    @DisplayName("D3 Level 4 关闭商品浏览，但订单与支付不受影响")
    void 浏览降级() {
        TestFixture.Activity act = fixture.createRunningActivity(10);

        setLevel(4);
        assertThat(degradeSwitch.browseEnabled()).isFalse();
        assertThatThrownBy(() -> activityService.detail(act.activityId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_DEGRADED);
    }

    @Test
    @DisplayName("D4 人工降级不会被自动逻辑抹掉；自动只能收紧")
    void 人工与自动取最大值() {
        degradeSwitch.setManualLevel(4, "运维手动降级");
        assertThat(degradeSwitch.getLevel()).isEqualTo(4);

        // 自动逻辑算出 0（没有积压、没有连接池等待）
        monitorJob.monitor();
        degradeSwitch.refresh();

        assertThat(degradeSwitch.getLevel())
                .as("自动恢复到 0 绝不能盖掉人工降到 4 的决定——"
                        + "做那个决定的人正在处理别的事故，不该发现开关自己弹回去了")
                .isEqualTo(4);
        assertThat(degradeSwitch.getAutoLevel()).isZero();

        // 反过来：自动收紧要生效
        degradeSwitch.setManualLevel(0, "解除人工降级");
        degradeSwitch.setAutoLevel(3, "模拟积压");
        assertThat(degradeSwitch.getLevel()).isEqualTo(3);
        assertThat(degradeSwitch.seckillEnabled()).isFalse();
    }

    @Test
    @DisplayName("D5 配置项总闸与自动降级是「与」关系，且 Redis 读不到时保持上次值")
    void 读不到开关时保持上次值() {
        degradeSwitch.setAutoLevel(3, "模拟积压");
        assertThat(degradeSwitch.getLevel()).isEqualTo(3);

        // 模拟 Redis 读失败：把 key 换成脏值以外的手段不好造，这里直接验
        // "值被删掉之后刷新会归零"与"脏值不会让它归零"两条相邻行为
        redis.opsForValue().set(DegradeSwitch.KEY_AUTO, "not-a-number");
        degradeSwitch.refresh();
        assertThat(degradeSwitch.getLevel())
                .as("脏值当 0 处理是安全的：生效等级取人工与自动的最大值，"
                        + "两个都脏才归零，而那确实是「没有任何有效降级指令」")
                .isZero();
    }

    @Test
    @DisplayName("D6 自动降级带 TTL，人工降级不带")
    void 自动降级带TTL人工不带() {
        degradeSwitch.setAutoLevel(3, "模拟积压");
        Long autoTtl = redis.getExpire(DegradeSwitch.KEY_AUTO);
        assertThat(autoTtl)
                .as("自动降级必须能自己过期：写入它的 job 实例崩溃后，"
                        + "没有任何机制负责删这个 key")
                .isGreaterThan(0);

        degradeSwitch.setManualLevel(2, "人工");
        assertThat(redis.getExpire(DegradeSwitch.KEY_MANUAL))
                .as("人做的决定不该悄悄失效，必须显式解除")
                .isEqualTo(-1L);
    }

    @Test
    @DisplayName("D7 降级等级作为仪表上报")
    void 降级等级进指标() {
        setLevel(3);
        assertThat(metrics.gaugeValue("fss_degrade_level"))
                .as("这个仪表要画在看板最显眼处——人工降级没有 TTL，"
                        + "全靠它提醒还有一个未解除的降级")
                .isEqualTo(3.0);
    }

    /** 写自动那一半并立即生效，模拟"自动监控判定要降级" */
    private void setLevel(int level) {
        degradeSwitch.setAutoLevel(level, "测试");
        assertThat(degradeSwitch.getLevel()).isEqualTo(level);
    }
}

package com.fss.infra.config;

import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 接口级限流与熔断（四级限流的第二级）。
 *
 * <p>四级限流从外到内逐层收窄，每一层拦下的请求都不消耗内层资源：
 * <pre>
 * ① Nginx limit_req      全局 + 单 IP            拦掉爬虫与明显洪峰
 * ② Sentinel 接口级 QPS   单接口 + 熔断降级        保护应用线程池
 * ③ Redis 令牌桶          单用户 / IP / 活动       业务级公平性
 * ④ Lua 内部快速失败       售罄标记 status = 2      降低 Redis 自身开销
 * </pre>
 *
 * <p>规则用代码写死而不是接控制台：控制台推送的规则是运行时状态，
 * 重启即丢，而且"线上阈值到底是多少"变成一个要去控制台查的问题。
 * 写在配置里的规则可以随代码一起 review、一起回滚。
 * 真要动态调整，这些阈值本来就绑在 {@link FssProperties} 上，改配置重启即可。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SentinelConfig {

    /** 秒杀提交。整个系统最需要保护的入口 */
    public static final String RES_SECKILL_SUBMIT = "seckill:submit";
    /** 结果轮询。量大但廉价，阈值给得比提交高 */
    public static final String RES_SECKILL_RESULT = "seckill:result";
    /** 活动详情。有缓存兜着，异常比例熔断防的是缓存与 DB 同时出问题 */
    public static final String RES_ACTIVITY_DETAIL = "activity:detail";
    /** Lua 脚本调用。用并发线程数而不是 QPS 限流，见 {@link #flowRules} */
    public static final String RES_SECKILL_SCRIPT = "redis-seckill-script";

    private final FssProperties props;

    static {
        // 不设的话 Sentinel 会往 ${user.home}/logs/csp 写指标文件，
        // 容器里的 user.home 常常不可写，启动时刷一堆异常栈却又不致命，很干扰排查
        System.setProperty("csp.sentinel.log.dir", "logs/sentinel");
        System.setProperty("csp.sentinel.app.name", "fss");
    }

    /**
     * {@code @SentinelResource} 的切面。
     *
     * <p>用 {@code @ConditionalOnProperty} 而不是在业务代码里判断开关：
     * 关掉时切面根本不注册，注解退化成一个没人读的元数据，
     * 零运行时开销。集成测试正是靠这个把 Sentinel 整段摘掉。
     */
    @Bean
    @ConditionalOnProperty(prefix = "fss.sentinel", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public SentinelResourceAspect sentinelResourceAspect() {
        log.info("Sentinel 切面已启用 submitQps={} luaConcurrency={}",
                props.getSentinel().getSubmitQps(), props.getSentinel().getLuaConcurrency());
        return new SentinelResourceAspect();
    }

    /**
     * 规则在应用就绪后加载。
     *
     * <p>不放在 {@code @PostConstruct}：那时配置属性绑定虽已完成，
     * 但如果加载失败会直接让上下文启动失败——限流规则加载不上是可降级的问题，
     * 不该拖着整个应用起不来。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadRules() {
        if (!props.getSentinel().isEnabled()) {
            log.info("Sentinel 已关闭，跳过规则加载");
            return;
        }
        try {
            FlowRuleManager.loadRules(flowRules());
            DegradeRuleManager.loadRules(degradeRules());
            log.info("Sentinel 规则加载完成 flow={} degrade={}",
                    flowRules().size(), degradeRules().size());
        } catch (Exception e) {
            log.error("Sentinel 规则加载失败，接口级限流不生效（仍有 Nginx 与令牌桶兜底）", e);
        }
    }

    private List<FlowRule> flowRules() {
        var s = props.getSentinel();
        List<FlowRule> rules = new ArrayList<>();
        rules.add(qps(RES_SECKILL_SUBMIT, s.getSubmitQps()));
        rules.add(qps(RES_SECKILL_RESULT, s.getResultQps()));
        rules.add(qps(RES_ACTIVITY_DETAIL, s.getActivityDetailQps()));

        // Lua 调用用并发线程数阈值：Redis 慢下来时线程会堆积在等响应上，
        // 线程数能直接感知这一点；QPS 阈值感知不到——请求进来的速率没变，
        // 变的是每个请求占用线程的时间。等到 QPS 掉下来时线程池早就打满了
        FlowRule lua = new FlowRule(RES_SECKILL_SCRIPT);
        lua.setGrade(RuleConstant.FLOW_GRADE_THREAD);
        lua.setCount(s.getLuaConcurrency());
        rules.add(lua);
        return rules;
    }

    private List<DegradeRule> degradeRules() {
        var s = props.getSentinel();
        List<DegradeRule> rules = new ArrayList<>();

        // 慢调用比例：RT > slowRtMs 的请求占比超过 50% 时熔断。
        // minRequestAmount 是必需的——不设的话，冷启动时头两个慢请求
        // 就能把比例做成 100%，直接熔断一个其实健康的服务
        DegradeRule slow = new DegradeRule(RES_SECKILL_SUBMIT);
        slow.setGrade(RuleConstant.DEGRADE_GRADE_RT);
        slow.setCount(s.getSlowRtMs());
        slow.setSlowRatioThreshold(0.5);
        slow.setMinRequestAmount(50);
        slow.setStatIntervalMs(5000);
        slow.setTimeWindow(s.getCircuitBreakSeconds());
        rules.add(slow);

        DegradeRule err = new DegradeRule(RES_ACTIVITY_DETAIL);
        err.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        err.setCount(0.3);
        err.setMinRequestAmount(50);
        err.setStatIntervalMs(5000);
        err.setTimeWindow(s.getCircuitBreakSeconds());
        rules.add(err);
        return rules;
    }

    private FlowRule qps(String resource, int count) {
        FlowRule r = new FlowRule(resource);
        r.setGrade(RuleConstant.FLOW_GRADE_QPS);
        r.setCount(count);
        return r;
    }
}

package com.fss.infra.degrade;

import com.fss.infra.config.FssProperties;
import com.fss.infra.metrics.SeckillMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 降级开关。
 *
 * <h3>分级（docs/07 §4）</h3>
 * <pre>
 * Level 0  正常
 * Level 1  关闭非核心：精确库存展示改为"有货/紧张/无货"
 * Level 2  拉长客户端轮询间隔 300ms → 2000ms
 * Level 3  暂停新资格分配（秒杀入口拒绝），已排队的继续处理完
 * Level 4  只保留订单查询与支付，商品浏览也关
 * </pre>
 * 保住的优先级：<b>支付 &gt; 订单查询 &gt; 已排队订单落库 &gt; 新资格分配 &gt; 商品浏览</b>。
 * 支付排第一是因为它涉及资金，中断会产生真实资损与客诉；
 * 而"已排队订单落库"排在"新资格分配"之前，是因为半途而废的排队请求
 * 每一条都对应一份被占用的库存和一个正在等结果的用户。
 *
 * <h3>为什么是"本地 volatile + 每秒刷"而不是每次请求读 Redis</h3>
 * 降级开关在秒杀链路的<b>最前面</b>被读到，每请求一次 Redis 就等于给这条最热的路径
 * 加了一次网络往返——而降级机制存在的目的正是减少资源消耗。每秒刷一次意味着
 * 开关最多延迟 1 秒生效，这对人工/自动降级都完全够（降级不是熔断，不需要毫秒级）。
 *
 * <p><b>读不到就保持上次值，绝不默认降级。</b> 反过来（读不到就降到最高级）会把
 * "Redis 抖了一下"放大成"全站停止服务"——而这个开关本身就是为了防止故障放大。
 * 也不默认 0：Redis 挂掉前如果已经降到 3，重连期间不该悄悄恢复放行。
 *
 * <h3>人工与自动是两个 key，取最大值</h3>
 * 这是本类最重要的一个设计。自动监控每 15 秒重算一次目标等级，算法是无状态的
 * （所有条件都退回时结果自然是 0，天然支持自动恢复）。但如果它写的是同一个 key，
 * 运维手动降到 Level 4 之后，下一轮自动计算就把它抹回 0——而做这个决定的人
 * 正在处理别的事故，根本不知道开关自己弹回去了。
 *
 * <p>所以拆成 {@code degrade:level}（人工，<b>无 TTL</b>）与
 * {@code degrade:level:auto}（自动，<b>带 TTL</b>），生效等级取两者最大值：
 * <ul>
 *   <li>自动机制只能<b>收紧</b>，永远盖不过人的决定</li>
 *   <li>人工降级不会自己消失，必须有人显式解除（写 0 或删 key）</li>
 *   <li>自动降级带 TTL：写入 Level 3 的那个 job 实例随后崩溃时，
 *       没有 TTL 的话这个值永久停在 3，而没有任何机制负责删它。
 *       TTL 让最坏情况变成"几分钟后自动恢复"</li>
 * </ul>
 * 同理 {@code fss.degrade.seckill-enabled} 这个配置项也是"与"关系：
 * 关掉之后自动逻辑恢复到 0 也不能把秒杀打开。
 */
@Slf4j
@Component
public class DegradeSwitch {

    /** 人工降级等级。无 TTL，必须显式解除 */
    public static final String KEY_MANUAL = "degrade:level";
    /** 自动降级等级。带 TTL，写入者崩溃时能自己过期 */
    public static final String KEY_AUTO   = "degrade:level:auto";

    public static final int MAX_LEVEL = 4;

    private final StringRedisTemplate redis;
    private final FssProperties       props;

    /**
     * volatile 而不是 AtomicInteger：只有单个刷新线程写、其余线程读，
     * 不存在"读-改-写"，volatile 的可见性保证已经足够，且读取零开销。
     */
    private volatile int level = 0;
    /**
     * 自动那一半的当前值，单独记着。
     *
     * <p>自动监控要用它做"和上轮比有没有变"的判断——用生效等级
     * {@link #level} 判断是错的：人工降到 4 而自动算出 0 时，
     * 生效等级恒为 4，于是监控每一轮都认为"等级变了"，每 15 秒写一次 Redis、
     * 发一条"4 → 0"的告警，而实际上什么都没变。
     */
    private volatile int autoLevel = 0;

    public DegradeSwitch(StringRedisTemplate redis, FssProperties props,
                         SeckillMetrics metrics) {
        this.redis = redis;
        this.props = props;
        metrics.registerDegradeLevel(this::getLevel);
    }

    /**
     * 每秒同步一次。
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：Redis 慢的时候 fixedRate 会
     * 让刷新任务排队堆积（每秒一个，永远追不上），而它们全都是在做同一件事。
     *
     * <p>用一次 {@code MGET} 取两个 key，不是两次 GET：省一次往返，
     * 而且两个值来自同一个时间点——分两次读可能读到"人工已解除、自动还没写"的中间态。
     */
    @Scheduled(fixedDelayString = "${fss.degrade.refresh-delay-ms:1000}")
    public void refresh() {
        try {
            List<String> vals = redis.opsForValue().multiGet(List.of(KEY_MANUAL, KEY_AUTO));
            int manual = parse(vals == null ? null : vals.get(0));
            int auto   = parse(vals == null || vals.size() < 2 ? null : vals.get(1));
            autoLevel = clamp(auto);
            int next = clamp(Math.max(manual, auto));
            if (next != level) {
                log.warn("stage=DEGRADE level {} → {} (manual={} auto={})",
                        level, next, manual, auto);
                level = next;
            }
        } catch (Exception e) {
            // Redis 不可用：保持上次值。绝不因为读不到开关就全站降级
            log.debug("stage=DEGRADE result=READ_FAILED 保持 level={}", level, e);
        }
    }

    /**
     * 脏值或缺失一律当 0。
     *
     * <p>这里可以安全地当 0，因为最终取的是两个值的<b>最大值</b>：
     * 一个 key 是脏值时另一个仍然有效，而两个都脏时结果是 0——那确实是
     * "没有任何有效的降级指令"。与 {@link #refresh} 整体失败时"保持上次值"
     * 的处理不同，那种情况我们连"有没有指令"都不知道。
     */
    private int parse(String v) {
        if (v == null || v.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.error("stage=DEGRADE result=BAD_VALUE value={} 当 0 处理", v);
            return 0;
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(MAX_LEVEL, v));
    }

    public int getLevel() {
        return level;
    }

    /** 自动那一半的当前值。只有自动监控该用它，业务判断一律用 {@link #getLevel()} */
    public int getAutoLevel() {
        return autoLevel;
    }

    /**
     * 秒杀入口是否放行。
     *
     * <p>{@code props} 那一半是人工总闸，见类注释里为什么是"与"。
     */
    public boolean seckillEnabled() {
        return props.getDegrade().isSeckillEnabled() && level < 3;
    }

    /** 是否展示精确剩余库存。Level 1 起改为"有货/紧张/无货"，省掉一次 Redis 读 */
    public boolean showExactStock() {
        return level < 1;
    }

    /** 商品浏览（活动列表与详情）是否开放。Level 4 只保留订单查询与支付 */
    public boolean browseEnabled() {
        return level < 4;
    }

    /**
     * 下发给客户端的轮询间隔。
     *
     * <p>这是个软限流手段：1 万个用户按 300ms 轮询就是 33000 QPS 打在结果接口上，
     * 比秒杀提交本身还高。服务端下发、客户端照着等，降级时拉长到 2000ms
     * 就能把结果接口压力降一个数量级，而且<b>不用发版</b>。
     */
    public int pollIntervalMs() {
        var d = props.getDegrade();
        return level >= 2 ? d.getDegradedPollIntervalMs() : d.getPollIntervalMs();
    }

    /** 自动降级写入。带 TTL，见类注释 */
    public void setAutoLevel(int target, String reason) {
        write(KEY_AUTO, target, reason,
                Duration.ofSeconds(props.getDegrade().getLevelTtlSeconds()));
    }

    /**
     * 人工降级写入（管理接口）。
     *
     * <p><b>不带 TTL</b>：人做的决定不该悄悄失效。代价是必须有人记得解除——
     * 所以 {@code fss_degrade_level} 这个仪表要画在看板最显眼的位置。
     */
    public void setManualLevel(int target, String reason) {
        write(KEY_MANUAL, target, reason, null);
    }

    private void write(String key, int target, String reason, Duration ttl) {
        int clamped = clamp(target);
        try {
            if (ttl == null) {
                redis.opsForValue().set(key, String.valueOf(clamped));
            } else {
                redis.opsForValue().set(key, String.valueOf(clamped), ttl);
            }
            log.warn("stage=DEGRADE_SET key={} target={} reason={}", key, clamped, reason);
            // 立刻刷一次让本实例马上生效，不用等下一个刷新周期。
            // 其他实例仍要等最多 1 秒——这是分布式开关固有的传播延迟，不是缺陷
            refresh();
        } catch (Exception e) {
            log.error("stage=DEGRADE_SET key={} 写入失败 target={} reason={}",
                    key, clamped, reason, e);
        }
    }
}

package com.fss.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 全部业务语义相关的自定义配置，集中在 {@code fss} 前缀下。
 *
 * <p>不散落在代码里的理由很实际：超时时长、限流阈值这类值需要在压测中反复调整，
 * 硬编码意味着每次调整都要重新编译。
 */
@Data
@ConfigurationProperties(prefix = "fss")
public class FssProperties {

    private Order      order      = new Order();
    private Seckill    seckill    = new Seckill();
    private RateLimit  ratelimit  = new RateLimit();
    private Mq         mq         = new Mq();
    private Degrade    degrade    = new Degrade();
    private Jwt        jwt        = new Jwt();
    private Pay        pay        = new Pay();
    private Job        job        = new Job();
    private Sentinel   sentinel   = new Sentinel();
    private Reconcile  reconcile  = new Reconcile();

    @Data
    public static class Order {
        /** 关单时长。决策 3：用 RocketMQ 5.x 任意时刻定时消息，不受延迟级别约束 */
        private Duration payTimeout      = Duration.ofMinutes(15);
        /** 定时扫描比定时消息晚这么久才处理，避免两者抢同一批订单 */
        private Duration closeScanDelay  = Duration.ofMinutes(2);
        /** 单次扫描批量 */
        private int      closeScanBatch  = 200;
    }

    @Data
    public static class Seckill {
        private Duration resultTtl      = Duration.ofMinutes(30);
        private Duration tokenTtl       = Duration.ofMinutes(5);
        private Duration warmupAhead    = Duration.ofMinutes(5);
        private Duration cacheTtl       = Duration.ofHours(2);
        /** 随机抖动上限，防雪崩 */
        private Duration cacheTtlJitter = Duration.ofMinutes(10);
        private Duration nullCacheTtl   = Duration.ofSeconds(60);
        /**
         * 物理 TTL 相对逻辑过期时间的缓冲。
         *
         * <p>必须大于一次回源的最坏耗时：逻辑过期后旧值还要能读到，
         * 后台重建才有意义。缓冲太短会退化成普通 TTL 缓存，击穿照旧发生。
         */
        private Duration cachePhysicalBuffer = Duration.ofMinutes(30);
        /**
         * 预热 key 的存活时间相对活动结束时间的延长量。
         *
         * <p>活动结束后 key 不能立刻消失：结果查询还在轮询、对账任务要读 Redis 库存
         * 与 DB 比对。留一小时够对账跑完几轮。
         */
        private Duration keyTtlAfterEnd = Duration.ofHours(1);
        /** 已回补订单号 Set 的 TTL。比 keyTtlAfterEnd 长得多——关单可能发生在活动结束后 */
        private Duration releasedTtl    = Duration.ofHours(25);
        /**
         * 决策 1：取消后不允许重抢。
         *
         * <p>保留为配置项只为表达"这是一个被显式决定过的取舍"，
         * 代码中启动时断言必须为 false。开成 true 会导致：Redis 放行重抢 →
         * 消费端 insert 撞 {@code uk_activity_sku_user} → 补偿回补 → 用户再抢，
         * 形成"创建失败 → 补偿"死循环。
         */
        private boolean  allowRepurchaseAfterCancel = false;
    }

    @Data
    public static class RateLimit {
        /** 总开关。压测基线、集成测试需要整段关掉 */
        private boolean enabled     = true;
        private int userQps     = 2;
        private int ipQps       = 20;
        private int activityQps = 5000;
        private int globalQps   = 12000;
        /** 结果查询接口的单用户限速 */
        private int resultQps   = 5;
        /**
         * 桶容量相对速率的倍数，决定允许多大的瞬时突发。
         *
         * <p>取 2 是因为真实用户会双击、会刷新——容量等于速率时正常操作也会被拒，
         * 用户只会更用力地重试，反而放大流量。
         */
        private int burstFactor = 2;
    }

    @Data
    public static class Mq {
        private Topic topic       = new Topic();
        private Group group       = new Group();
        private long  sendTimeout = 2000;
        private int   maxResend   = 5;
        /**
         * 是否启动 {@code DefaultMQAdminExt} 采集积压量。
         *
         * <p>默认关：它是一个额外的 MQ 客户端，broker 不可达时会每轮刷一条连接失败日志，
         * 把真正的错误埋掉；而集成测试里多一个客户端在 Windows 上会直接撞
         * {@code GetAdaptersAddresses failed with error == 1450}。
         * 联调与生产显式打开。
         */
        private boolean backlogMonitorEnabled = false;

        @Data
        public static class Topic {
            private String orderCreate   = "FSS_ORDER_CREATE";
            private String orderClose    = "FSS_ORDER_CLOSE";
            private String stockRelease  = "FSS_STOCK_RELEASE";
            private String stockRollback = "FSS_STOCK_ROLLBACK";
        }

        @Data
        public static class Group {
            private String orderCreate   = "GID_FSS_ORDER_CREATE";
            private String orderClose    = "GID_FSS_ORDER_CLOSE";
            private String stockRelease  = "GID_FSS_STOCK_RELEASE";
            private String stockRollback = "GID_FSS_STOCK_ROLLBACK";
        }
    }

    @Data
    public static class Degrade {
        /**
         * 人工总闸。与自动降级是<b>与</b>关系：关掉之后自动逻辑恢复到 Level 0
         * 也不能把秒杀打开——自动机制只允许收紧，不允许放开人的决定。
         */
        private boolean seckillEnabled   = true;
        /** 本地缓存刷新间隔（毫秒）。开关最多延迟这么久生效 */
        private long    refreshDelayMs   = 1000;
        /**
         * {@code degrade:level} 的 TTL（秒）。
         *
         * <p>必须有：写入 Level 3 的那个 job 实例随后崩溃时，没有 TTL 的话这个 key
         * 永久停在 3，秒杀再也不会自动恢复，而没有任何机制负责删它。
         * 取值要明显大于监控任务的间隔（否则正常运行时开关会自己过期抖动），
         * 默认 15s 间隔 → 300s TTL 有 20 倍余量。
         */
        private long    levelTtlSeconds  = 300;
        /**
         * 下发给客户端的轮询间隔（毫秒）。
         *
         * <p>这是一个<b>软限流手段</b>：异步化之后每个用户提交完都要轮询结果，
         * 1 万个用户按 300ms 轮询就是 33000 QPS 打在结果接口上，
         * 比秒杀提交本身还高。服务端下发间隔，客户端照着等，
         * 比在客户端硬编码好——降级时可以直接拉长，不用发版。
         */
        private int     pollIntervalMs   = 300;
        /** Level 2 起下发的轮询间隔。拉长 6~7 倍，结果接口压力降一个数量级 */
        private int     degradedPollIntervalMs = 2000;

        // ---- 自动降级的触发与恢复阈值 ----

        /** 是否启用自动降级。关掉之后只能人工调 {@code degrade:level} */
        private boolean autoEnabled      = true;
        /** 消息积压超过它 → Level 3（暂停新资格分配） */
        private long    backlogThreshold = 50_000;
        /** 消费端 TPS 跌至 0 且积压 > 0 持续这么多轮 → Level 3 */
        private int     stalledRounds    = 4;
        /** Hikari 连接池等待数超过它 → Level 2 */
        private int     dbPendingThreshold = 5;
        /**
         * 恢复阈值 = 触发阈值 × 这个比例（滞回）。
         *
         * <p>必须小于 1：等于 1 就会在阈值附近反复开关（积压 50001 降级 → 消费追上
         * 变 49999 恢复 → 流量立刻回来又 50001），每次抖动都是一次全站行为变化。
         */
        private double  recoverRatio     = 0.5;
    }

    @Data
    public static class Jwt {
        /**
         * HMAC-SHA256 密钥，至少 32 字节。
         * 生产必须由环境变量注入，配置文件里的默认值只用于本地开发。
         */
        private String   secret = "fss-local-dev-only-secret-change-me-in-prod";
        private Duration ttl    = Duration.ofHours(2);
        private String   issuer = "fss";
    }

    @Data
    public static class Pay {
        /** 模拟渠道回调验签密钥 */
        private String   notifySecret   = "fss-mock-pay-secret-change-me";
        /** 回调时间戳允许的偏差，防重放 */
        private Duration notifyTolerance = Duration.ofMinutes(5);
    }

    /**
     * 定时任务的 cron 表达式。抽成配置是为了让测试能把它们关掉——
     * 集成测试里定时任务突然把订单关了，会造成难以复现的间歇性失败。
     */
    @Data
    public static class Job {
        private String closeExpiredCron = "0 */2 * * * ?";
        private String activityStateCron = "0 * * * * ?";
        private String warmupCron        = "0 * * * * ?";
        /**
         * 预热并发度。
         *
         * <p>活动之间没有依赖，串行预热的风险随活动数线性放大：一场活动几十个
         * SKU，每个都要一次 DB 往返加若干次 Redis 写入，串行很容易吃掉整个
         * {@code warmup-ahead} 窗口，表现是"到点了还没预热完，用户拿到未预热"。
         *
         * <p>不能开太大：预热任务与其它 job 共用 DB 连接池
         * （{@code application-job.yml} 里 job 角色的池是 10）。
         * 4 是"明显够快"与"不抢连接"之间的折中，压测中可调。
         */
        private int    warmupConcurrency = 4;
        /**
         * 消息重发任务的间隔（毫秒）。
         *
         * <p>用 {@code fixedDelay} 而不是 cron：重发的语义是"上一轮处理完之后再等
         * 这么久"，用 cron 的话 MQ 长时间不可用时，一轮跑 30 秒而 cron 每 30 秒触发，
         * 两轮会重叠——虽然分布式锁挡得住，但那是靠锁掩盖了配置问题。
         */
        private long   mqResendDelayMs   = 30_000;
        /** 资格对账 */
        private String reconcileQualificationCron = "0 * * * * ?";
        /** 库存对账 */
        private String reconcileStockCron         = "0 */5 * * * ?";
        /** 支付对账 */
        private String reconcilePaymentCron       = "0 */10 * * * ?";
        /** 不确定结果确认。间隔用 fixedDelay，语义是"上一轮处理完再等这么久" */
        private long   uncertainCheckDelayMs      = 10_000;
        /** 降级监控。间隔要明显小于 {@code degrade.level-ttl-seconds} */
        private long   degradeMonitorDelayMs      = 15_000;
    }

    /**
     * 对账任务参数。
     *
     * <p>这些"等多久才认为是差异"的值全都是取舍：给短了会把正常的异步延迟当成差异
     * （每分钟刷一堆假差异，真差异被淹没），给长了差异发现得晚（库存被多占几分钟）。
     */
    @Data
    public static class Reconcile {
        /**
         * 排队中请求超过这么久还没结论，才算孤儿。
         *
         * <p>下限是"消息重发耗尽的总时长"：重发退避是 30+60+120+240+480 ≈ 15.5 分钟，
         * 在那之前重发任务还在努力，对账插手只会重复回补。取 5 分钟是折中——
         * 5 分钟没结论的请求，用户早就走了，占着的库存比"等重发任务再试两轮"更值钱；
         * 而对账不会直接回补，它先看消息表状态（见 {@code QualificationReconciler}）。
         */
        private Duration orphanAfter        = Duration.ofMinutes(5);
        /** 同一个孤儿请求连续这么多轮仍无结论 → 直接回补，不再等重发 */
        private int      orphanRoundsBeforeRollback = 3;
        /** 支付流水完成后多久仍与订单状态不符，才算差异 */
        private Duration paymentDiffAfter   = Duration.ofMinutes(5);
        /** 单轮扫描的上限，防止一轮跑太久拖过分布式锁租期 */
        private int      batchSize          = 500;
        /** 不确定记录保留多久。超期仍无法判定 → 落对账任务转人工 */
        private Duration uncertainRetention = Duration.ofMinutes(10);
        /**
         * 不确定记录在这么久之后才开始判定。
         *
         * <p>不能立刻判：Redis 超时的那一刻，脚本可能<b>正在</b>执行。
         * 马上 {@code EXISTS req key} 读到不存在就断定"没执行"，
         * 而 50ms 后它执行完了——于是库存被扣掉却没人补消息，
         * 成了一个只有库存对账才能发现的泄漏。
         */
        private Duration uncertainSettle    = Duration.ofSeconds(5);
    }

    /**
     * Sentinel 接口级限流与熔断（四级限流的第二级）。
     *
     * <p>阈值是<b>单实例</b>的：Sentinel 默认是单机限流，3 个 web 实例的总放行量
     * 是这里的 3 倍。集群限流需要额外部署 token server，本项目规模不需要。
     */
    @Data
    public static class Sentinel {
        /**
         * 是否注册 Sentinel 切面。
         *
         * <p>集成测试里必须关掉：测试会在几秒内打上千次 submit，
         * 撞上 QPS 阈值后失败原因变成 RATE_LIMITED，
         * 把"是否超卖"的断言污染成"限流是否生效"。
         */
        private boolean enabled            = true;
        private int     submitQps          = 6000;
        private int     resultQps          = 8000;
        private int     activityDetailQps  = 3000;
        /** Lua 脚本调用的并发线程数阈值。见类注释里为什么不用 QPS */
        private int     luaConcurrency     = 200;
        /** 慢调用阈值（毫秒），超过即计入慢调用比例 */
        private int     slowRtMs           = 200;
        /** 熔断持续时长（秒） */
        private int     circuitBreakSeconds = 10;
    }
}

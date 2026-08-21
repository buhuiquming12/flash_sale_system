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

    private Order     order     = new Order();
    private Seckill   seckill   = new Seckill();
    private RateLimit ratelimit = new RateLimit();
    private Mq        mq        = new Mq();
    private Degrade   degrade   = new Degrade();
    private Jwt       jwt       = new Jwt();
    private Pay       pay       = new Pay();
    private Job       job       = new Job();
    private Sentinel  sentinel  = new Sentinel();

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
        /** 总开关，可运行时关闭秒杀入口 */
        private boolean seckillEnabled   = true;
        private long    backlogThreshold = 50_000;
        /**
         * 下发给客户端的轮询间隔（毫秒）。
         *
         * <p>这是一个<b>软限流手段</b>：异步化之后每个用户提交完都要轮询结果，
         * 1 万个用户按 300ms 轮询就是 33000 QPS 打在结果接口上，
         * 比秒杀提交本身还高。服务端下发间隔，客户端照着等，
         * 比在客户端硬编码好——降级时（docs/07 Level 2）可以直接拉长到 2000ms，
         * 不用发版。
         */
        private int     pollIntervalMs   = 300;
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
         * 消息重发任务的间隔（毫秒）。
         *
         * <p>用 {@code fixedDelay} 而不是 cron：重发的语义是"上一轮处理完之后再等
         * 这么久"，用 cron 的话 MQ 长时间不可用时，一轮跑 30 秒而 cron 每 30 秒触发，
         * 两轮会重叠——虽然分布式锁挡得住，但那是靠锁掩盖了配置问题。
         */
        private long   mqResendDelayMs   = 30_000;
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

package com.fss.infra.redis;

/**
 * Redis Key 集中定义。
 *
 * <p><b>为什么必须集中</b>：秒杀相关的四个 key 由同一段 Lua 一次性操作，
 * 它们的 hash tag 必须逐字节一致。散落在各处手写字符串拼接时，
 * 只要有一处写成 {@code "seckill:stock:" + activityId + ":" + skuId}
 * （少了花括号），单机 Redis 上一切正常，上了 Cluster 立刻报
 * "CROSSSLOT Keys in request don't hash to the same slot"——
 * 而这种错误只在生产环境的集群上才会出现。
 *
 * <p><b>hash tag 的作用</b>：Redis Cluster 按 key 的 CRC16 分槽，但如果 key 里含有
 * {@code {...}}，只用花括号内的内容计算槽位。把 {@code {activityId:skuId}} 作为 tag，
 * 同一个秒杀商品的元数据、库存、购买标记、请求结果就必然落在同一个槽，
 * Lua 多 key 操作才合法（对应设计决策 4）。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 秒杀商品元数据 Hash：status / startTime / endTime / price / limitPerUser / totalStock / version */
    public static String goods(long activityId, long skuId) {
        return "seckill:goods:" + tag(activityId, skuId);
    }

    /** 可售库存 String，整数。Lua 用 DECRBY / INCRBY 操作 */
    public static String stock(long activityId, long skuId) {
        return "seckill:stock:" + tag(activityId, skuId);
    }

    /** 用户已购数量 Hash：userId → qty。一人一单靠它拦截 */
    public static String bought(long activityId, long skuId) {
        return "seckill:bought:" + tag(activityId, skuId);
    }

    /** 单次请求的处理结果 Hash，供客户端轮询 */
    public static String request(long activityId, long skuId, String requestNo) {
        return "seckill:req:" + tag(activityId, skuId) + ":" + requestNo;
    }

    /** 已回补订单号 Set，取消回补的幂等凭据 */
    public static String released(long activityId, long skuId) {
        return "seckill:released:" + tag(activityId, skuId);
    }

    /**
     * Redis 调用结果不确定的请求集合（ZSet，score = 记录时刻的 epoch millis）。
     *
     * <p><b>不带 hash tag</b>：它跨活动跨 SKU，是一张全局待办清单，
     * 加 tag 反而要按活动开 N 个 key、扫描时不知道该扫哪些。
     * 它也不参与任何 Lua 多 key 操作，单独 ZADD / ZRANGEBYSCORE / ZREM 即可。
     */
    public static String uncertain() {
        return "seckill:uncertain";
    }

    /** 资格对账里"同一孤儿请求连续几轮无结论"的计数。TTL 1h，防每轮都重发造成消息风暴 */
    public static String orphanCount(String requestNo) {
        return "reconcile:orphan:count:" + requestNo;
    }

    /** 活动详情缓存（逻辑过期包装） */
    public static String activityDetail(long activityId) {
        return "activity:detail:" + activityId;
    }

    /** 缓存重建单飞锁 */
    public static String cacheRebuildLock(String cacheKey) {
        return "lock:cache:" + cacheKey;
    }

    /** 预热分布式锁 */
    public static String warmupLock(long activityId) {
        return "lock:warmup:" + activityId;
    }

    /** 定时任务分布式锁 */
    public static String jobLock(String name) {
        return "lock:job:" + name;
    }

    /**
     * 秒杀令牌。
     *
     * <p>不带 hash tag：它不参与秒杀 Lua 的多 key 操作，单独 GETDEL 即可。
     * 硬加 tag 反而会把大量令牌 key 挤到同一个槽，破坏 Cluster 的负载均衡。
     */
    public static String seckillToken(long userId, long activityId, long skuId) {
        return "seckill:token:" + userId + ":" + activityId + ":" + skuId;
    }

    public static String rateUser(long userId, long activityId) {
        return "rate:user:" + userId + ":" + activityId;
    }

    public static String rateIp(String ip) {
        return "rate:ip:" + ip;
    }

    public static String rateActivity(long activityId) {
        return "rate:activity:" + activityId;
    }

    /** JWT 主动失效：签发时登记，登出时删除 */
    public static String jwtActive(String jti) {
        return "jwt:active:" + jti;
    }

    private static String tag(long activityId, long skuId) {
        return "{" + activityId + ":" + skuId + "}";
    }
}

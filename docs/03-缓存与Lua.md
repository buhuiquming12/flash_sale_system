# 03 缓存与 Lua 脚本

## 1. Key 设计

所有秒杀相关 key 带统一 hash tag `{activityId:skuId}`，保证同一 Lua 脚本涉及的
多个 key 落在同一 Redis Cluster slot（[决策 4](00-设计总览.md#决策-4redis-key-统一-hash-tag保证-lua-多-key-同-slot)）。

| Key | 类型 | 内容 | TTL |
| --- | --- | --- | --- |
| `seckill:goods:{1001:2001}` | Hash | 活动与商品元数据 | 活动结束 + 1h |
| `seckill:stock:{1001:2001}` | String | 可售库存整数 | 活动结束 + 1h |
| `seckill:bought:{1001:2001}` | Hash | `userId → 已购数量` | 活动结束 + 24h |
| `seckill:req:{1001:2001}:R2026...` | Hash | 请求处理结果 | 30 min |
| `seckill:released:{1001:2001}` | Set | 已回补的 orderNo | 活动结束 + 24h |
| `activity:detail:1001` | String | 活动详情 JSON（含逻辑过期） | 2h ± 10min |
| `activity:list:page:1` | String | 活动列表 JSON | 5 min |
| `sku:detail:2001` | String | SKU 详情 JSON | 2h ± 10min |
| `rate:user:{uid}:{aid}` | String | 用户令牌桶 | 2s |
| `rate:ip:{ip}` | String | IP 令牌桶 | 2s |
| `rate:activity:{aid}` | String | 活动令牌桶 | 2s |
| `seckill:token:{uid}:{aid}:{sid}` | String | 秒杀令牌 | 5 min |
| `lock:warmup:1001` | String | 预热分布式锁 | 5 min |

> **`seckill:bought` 的 TTL 必须由脚本自己设。** 这个 Hash 是 `HINCRBY` 惰性创建的，
> 预热阶段无法预先建一个空 Hash（Redis 里空 Hash 不存在），所以预热写不进 TTL。
> 脚本 A 在成功预扣后补一次 `EXPIRE`，过期时间用脚本里已经读到的 `endTime` 算，
> 不额外传参——避免"Java 传的 TTL 与 Redis 里的 endTime 不一致"。
> 漏掉这一步的后果是购买标记永久驻留：一场十万人的活动约 3MB，做几十场就撑爆内存，
> 而 `maxmemory-policy` 是 `noeviction`，撑爆意味着写入直接失败。
>
> 所有 key 的 TTL 计算都要有**下限**。对已结束的活动执行预热（人工补数据、排查问题）时
> `endTime - now` 是负数，直接拿去 `EXPIRE` 会让 Redis 报 `invalid expire time`，
> 预热在第一个商品上就中断。

### `seckill:goods` 字段

```
status        1=在售 0=停售 2=售罄
startTime     活动开始 epoch millis
endTime       活动结束 epoch millis
price         秒杀价，字符串形式的 decimal
limitPerUser  每人限购
totalStock    活动总库存（对账用，脚本不修改）
version       预热版本
```

把开始/结束时间放进 Redis，是为了让"活动是否在进行中"的判断也在 Lua 内完成。
否则应用层判断时间、Redis 判断库存，两者之间存在时间窗口，活动结束瞬间仍可能
放行请求。

### `seckill:req` 字段

```
status     0=排队中 1=成功 2=库存不足 3=重复购买 4=创建失败 5=已补偿
userId
orderNo    成功后填充
reason     失败原因
traceId
ts         最后更新时间
```

## 2. 脚本 A：秒杀资格判定与预扣

一次调用完成六件事：活动校验、时间校验、一人一单校验、库存校验、库存预扣、
写请求记录。这是整个系统唯一的资格分配入口。

`resources/lua/seckill.lua`

```lua
-- KEYS[1] seckill:goods:{a:s}
-- KEYS[2] seckill:stock:{a:s}
-- KEYS[3] seckill:bought:{a:s}
-- KEYS[4] seckill:req:{a:s}:<requestNo>
-- ARGV[1] userId
-- ARGV[2] quantity
-- ARGV[3] requestNo
-- ARGV[4] traceId
-- ARGV[5] request result ttl (seconds)
--
-- 返回: { code, remainStock }
--   0  成功预扣
--  -1  活动/商品未预热或不存在
--  -2  商品已被管理员停售
--  -3  活动未开始
--  -4  活动已结束
--  -5  重复购买（已达限购）
--  -6  库存不足
--  -7  请求号重复（同一 requestNo 已处理过）
--  -8  已售罄（预扣归零时打的快速失败标记）

local goodsKey  = KEYS[1]
local stockKey  = KEYS[2]
local boughtKey = KEYS[3]
local reqKey    = KEYS[4]

local userId    = ARGV[1]
local qty       = tonumber(ARGV[2])
local requestNo = ARGV[3]
local traceId   = ARGV[4]
local reqTtl    = tonumber(ARGV[5])

-- 0. 请求号幂等：同一 requestNo 重放直接返回已有结论
if redis.call('EXISTS', reqKey) == 1 then
    return { -7, tonumber(redis.call('GET', stockKey) or '0') }
end

-- 1. 元数据必须已预热
if redis.call('EXISTS', goodsKey) == 0 then
    return { -1, 0 }
end

local meta = redis.call('HMGET', goodsKey,
        'status', 'startTime', 'endTime', 'limitPerUser')
local status       = tonumber(meta[1])
local startTime    = tonumber(meta[2])
local endTime      = tonumber(meta[3])
local limitPerUser = tonumber(meta[4]) or 1

if status ~= 1 then
    return { -2, 0 }
end
```

（实现里这一段拆成两个判断：`status == 2` 返回 `-8`（售罄），其余非 1 返回 `-2`（停售）。
合并成一个码会让"卖光了"对外显示成"商品已停售"，文案是错的。见下文「三处容易写错的地方」。）

```lua
-- 2. 时间窗口。用 Redis 自身时钟，避免多应用实例时钟漂移
--    需 Redis >= 5（effects replication 后 TIME 可在写脚本中调用）
local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

if now < startTime then
    return { -3, 0 }
end
if now >= endTime then
    return { -4, 0 }
end

-- 3. 一人一单：必须在扣库存之前，否则重复请求会白吃库存
local bought = tonumber(redis.call('HGET', boughtKey, userId) or '0')
if bought + qty > limitPerUser then
    return { -5, 0 }
end

-- 4. 库存校验
local stock = tonumber(redis.call('GET', stockKey) or '0')
if stock < qty then
    return { -6, 0 }
end

-- 5. 预扣 + 标记 + 写请求记录，此后三者对外原子可见
local remain = redis.call('DECRBY', stockKey, qty)
redis.call('HINCRBY', boughtKey, userId, qty)

if remain <= 0 then
    redis.call('HSET', goodsKey, 'status', 2)   -- 售罄，后续请求走 -2 快速失败
end

redis.call('HSET', reqKey,
        'status',  0,
        'userId',  userId,
        'traceId', traceId,
        'ts',      now)
redis.call('EXPIRE', reqKey, reqTtl)

return { 0, remain }
```

### 三处容易写错的地方

**为什么先查 `bought` 再查 `stock`**：反过来的话，同一用户重复提交时先扣掉库存
再发现重复，需要回滚 `DECRBY`。虽然 Lua 内可以补回，但如果代码遗漏就是库存泄漏。
先判重复，从结构上消除这种可能。

**为什么售罄时改 `status = 2`**：库存为 0 后，后续 9000 个请求仍会走完
"EXISTS → HMGET → TIME → HGET → GET" 五次操作。改成 `status = 2` 后，
第三步就返回，Redis 单次脚本的操作数从 5 降到 2，热点活动下能显著降低 Redis CPU。
注意回补库存时必须把 `status` 改回 1。

**售罄与停售必须是两个返回码**。上面的 `status ~= 1 → -2` 把"卖光了"和
"管理员下架了"合并成同一个结论，于是活动一售罄，用户看到的提示是"商品已停售"——
客服会收到一堆"明明还有货为什么下架了"的工单。实现里多一次整数比较把它拆成
`-8`（售罄，对外仍是"库存不足"）和 `-2`（停售），代价可以忽略。

**为什么不用 `redis.call('SET', stockKey, stock - qty)`**：`DECRBY` 返回扣减后的值，
一次调用同时完成写入和读取剩余量，用于判断是否售罄。`SET` 还要再 `GET` 一次。

## 3. 脚本 B：补偿回补（ROLLBACK）

用于"Redis 预扣成功但订单最终没能创建"。归还库存**并且**归还用户购买资格
（[决策 2](00-设计总览.md#决策-2库存回补拆成两种语义)）。

`resources/lua/rollback.lua`

```lua
-- KEYS[1] seckill:stock:{a:s}
-- KEYS[2] seckill:bought:{a:s}
-- KEYS[3] seckill:req:{a:s}:<requestNo>
-- KEYS[4] seckill:goods:{a:s}
-- ARGV[1] userId
-- ARGV[2] quantity
-- ARGV[3] reason
-- ARGV[4] keepBought：1 = 保留用户购买标记，0 = 归还
--
-- 返回 0=已回补  1=状态不允许回补（幂等命中，无操作）

local stockKey  = KEYS[1]
local boughtKey = KEYS[2]
local reqKey    = KEYS[3]
local goodsKey  = KEYS[4]

local userId = ARGV[1]
local qty    = tonumber(ARGV[2])
local reason = ARGV[3]

-- 只有"排队中"的请求可以回补。
-- 已成功(1)不能回补，已补偿(5)不能重复回补，失败态(2/3)本就没扣过库存。
if redis.call('EXISTS', reqKey) == 0 then
    return 1
end
local status = tonumber(redis.call('HGET', reqKey, 'status'))
if status ~= 0 then
    return 1
end

-- 先改状态，把自己变成唯一执行者
redis.call('HSET', reqKey, 'status', 5, 'reason', reason)

redis.call('INCRBY', stockKey, qty)

local left = redis.call('HINCRBY', boughtKey, userId, -qty)
if left <= 0 then
    redis.call('HDEL', boughtKey, userId)
end

-- 库存从 0 恢复，取消售罄标记
if tonumber(redis.call('HGET', goodsKey, 'status') or '1') == 2 then
    redis.call('HSET', goodsKey, 'status', 1)
end

return 0
```

**幂等靠请求状态机而非计数器**：`status ~= 0 then return 1` 这一句保证无论回补
消息重复投递多少次，`INCRBY` 只执行一次。状态先改再回补，Lua 的原子性保证中间
没有其他脚本能观察到"status 仍是 0 但库存已加"的状态。

**`keepBought` 参数是实现时补上的，少了它会死循环。** 落库失败原因是
`ALREADY_BOUGHT`（DB 侧 `uk_activity_sku_user` 冲突，通常因为 Redis 的 `bought`
标记丢了而 DB 里那张已取消的订单还占着唯一键）时，如果连购买资格一起归还：
用户重抢 → Redis 放行 → DB 又冲突 → 又回补 → 用户再抢，无限循环。
这种**确定性失败必须只还库存、不还资格**；其余失败（系统异常、商品不存在）
用户没有责任，资格要还给他。

**脚本 B 还需要一个 `failStatus` 参数。** 上面固定把请求置成 5（COMPENSATED，
"系统繁忙已退回"），而消费端同时会往 `t_seckill_request` 落一条 status=3
（"已参与过本次秒杀"）。同一个请求在 Redis 和 DB 里就有了两个不同的结论，
查询接口返回哪个取决于 Redis 的 30 分钟 TTL 有没有到——而它偏向更坏的那一边：
用户看到"系统繁忙"会不停重试，看到"您已参与过"就不会。
确定性失败写具体码（2 / 3），只有系统原因的回补才写 5。

## 4. 脚本 C：取消回补（RELEASE）

用于订单超时关闭、用户取消。只归还库存，**保留**用户购买标记。

`resources/lua/release.lua`

```lua
-- KEYS[1] seckill:stock:{a:s}
-- KEYS[2] seckill:released:{a:s}
-- KEYS[3] seckill:goods:{a:s}
-- ARGV[1] orderNo
-- ARGV[2] quantity
-- ARGV[3] released set ttl (seconds)
--
-- 返回 0=已回补  1=该订单已回补过（幂等命中）

local stockKey    = KEYS[1]
local releasedKey = KEYS[2]
local goodsKey    = KEYS[3]

local orderNo = ARGV[1]
local qty     = tonumber(ARGV[2])
local ttl     = tonumber(ARGV[3])

-- SADD 返回 0 表示已存在，天然幂等
if redis.call('SADD', releasedKey, orderNo) == 0 then
    return 1
end
redis.call('EXPIRE', releasedKey, ttl)

redis.call('INCRBY', stockKey, qty)

if tonumber(redis.call('HGET', goodsKey, 'status') or '1') == 2 then
    redis.call('HSET', goodsKey, 'status', 1)
end

-- 注意：不动 seckill:bought。取消后不允许重抢（决策 1），
-- 用户资格已消耗，库存回池给其他用户。

return 0
```

## 5. 脚本 D：结果写入

消费端处理完订单后回写请求结果，供客户端轮询。用条件写避免覆盖终态。

`resources/lua/write_result.lua`

```lua
-- KEYS[1] seckill:req:{a:s}:<requestNo>
-- ARGV[1] newStatus
-- ARGV[2] orderNo (可为空串)
-- ARGV[3] reason  (可为空串)
-- ARGV[4] ttl
-- ARGV[5] userId
-- 返回 0=写入成功 1=已是终态，不覆盖

if redis.call('EXISTS', KEYS[1]) == 0 then
    -- 结果已过期或从未存在，重建一条（客户端可能仍在轮询）
    redis.call('HSET', KEYS[1], 'status', ARGV[1], 'orderNo', ARGV[2], 'reason', ARGV[3])
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
    return 0
end

local cur = tonumber(redis.call('HGET', KEYS[1], 'status'))
if cur ~= 0 then
    return 1      -- 只有"排队中"可以被推进
end

redis.call('HSET', KEYS[1], 'status', ARGV[1], 'orderNo', ARGV[2], 'reason', ARGV[3])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
return 0
```

**重建那条分支必须一起写 `userId`**。查询接口读到结论后要做归属校验
（不能让 A 查到 B 的秒杀结论），而被脚本 A 在时间/库存校验阶段拒绝的请求
根本没创建过 `req` key —— 它的 `userId` 只能由这里补上。漏了这个字段，
归属校验必然失败，表现是"明明失败了却查不到失败原因"，客户端一路轮询到超时。

## 6. 脚本 E：令牌桶限流

`resources/lua/token_bucket.lua`

```lua
-- KEYS[1] 桶 key
-- ARGV[1] 容量
-- ARGV[2] 每秒填充速率
-- ARGV[3] 本次请求令牌数
-- 返回 1=放行 0=拒绝

local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])
local need     = tonumber(ARGV[3])

local t   = redis.call('TIME')
local now = tonumber(t[1]) + tonumber(t[2]) / 1000000

local bucket    = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens    = tonumber(bucket[1])
local lastTs    = tonumber(bucket[2])

if tokens == nil then
    tokens = capacity
    lastTs = now
else
    local delta = math.max(0, now - lastTs)
    tokens = math.min(capacity, tokens + delta * rate)
    lastTs = now
end

local allowed = 0
if tokens >= need then
    tokens  = tokens - need
    allowed = 1
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', lastTs)
redis.call('EXPIRE', KEYS[1], math.ceil(capacity / rate) + 2)

return allowed
```

比固定窗口计数器好在不会有窗口边界的双倍突发，比滑动窗口日志省内存。

**`tokens` 与 `ts` 回写时要显式 `tostring`。** 两者都是小数，不同 Redis 版本把
Lua number 传给 `redis.call` 时用过 `%.14g` / `%.17g` 等不同格式，依赖隐式转换的
精度不是好主意。`ts` 一旦被截断到整秒，下一次算出的 `delta` 最多会多出 1 秒，
等于每次调用白送一秒的令牌，限流器就漏成筛子了——而这种漏只在"调用间隔小于 1 秒"
时出现，功能测试完全看不出来。

## 7. 脚本加载与调用

用 `DefaultRedisScript` + `EVALSHA`，脚本只在首次加载时上传。

> 这一句成立有个前提：脚本执行器得换成 `EvalShaScriptExecutor`。Spring 自带的执行器
> 在 `NOSCRIPT` 之后回落 `EVAL`，而那条路会按平台默认编码把正文转一遍码，
> 于是 Redis 缓存的 sha1 和应用发的 sha1 永远对不上，**每次调用都重传整段正文**。
> 阶段五压测才发现，成因与修法见 [docs/09 §5](09-压测报告.md)。

```java
@Configuration
public class SeckillScriptConfig {

    @Bean("seckillScript")
    public RedisScript<List> seckillScript() {
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seckill.lua")));
        s.setResultType(List.class);
        return s;
    }
    // rollbackScript / releaseScript / writeResultScript / tokenBucketScript 同理
}
```

```java
@Component
@RequiredArgsConstructor
public class SeckillExecutor {

    private final StringRedisTemplate redis;
    private final RedisScript<List> seckillScript;
    private final FssProperties props;

    public SeckillOutcome trySeckill(long activityId, long skuId, long userId,
                                     int qty, String requestNo, String traceId) {
        String tag = activityId + ":" + skuId;
        List<String> keys = List.of(
                "seckill:goods:{"  + tag + "}",
                "seckill:stock:{"  + tag + "}",
                "seckill:bought:{" + tag + "}",
                "seckill:req:{"    + tag + "}:" + requestNo);

        List<Long> r = redis.execute(seckillScript, keys,
                String.valueOf(userId), String.valueOf(qty), requestNo, traceId,
                String.valueOf(props.getSeckill().getResultTtl().toSeconds()));

        int code = r.get(0).intValue();
        long remain = r.size() > 1 ? r.get(1) : 0L;
        return SeckillOutcome.of(code, remain);
    }
}
```

`redis.execute` 抛异常（超时、连接失败）时**不能当成失败静默返回**：此时无法确定
脚本是否已执行。处理方式见 [04-核心流程](04-核心流程.md#5-redis-调用结果不确定时怎么办)。

## 8. 活动预热

预热任务在活动开始前 `warmup-ahead`（默认 5 分钟）执行，由 job 角色触发。

```java
@Scheduled(cron = "${fss.job.warmup-cron:0 * * * * ?}")
@DistributedLock(key = "warmup", leaseSeconds = 120)
public void warmup() {
    LocalDateTime deadline = LocalDateTime.now().plus(props.getSeckill().getWarmupAhead());
    List<SeckillActivity> list = activityMapper.selectReadyBefore(deadline);
    for (SeckillActivity a : list) {
        try {
            warmupService.warmupOne(a.getId());
        } catch (Exception e) {
            log.error("预热失败 activityId={}", a.getId(), e);
            activityMapper.updateWarmupState(a.getId(), WarmupState.FAILED);
            alarm.send("活动预热失败", a.getId());
        }
    }
}
```

`warmupOne` 的关键约束：**可重复执行，但不能把已扣减的库存重置回初始值。**

```java
@Transactional(readOnly = true)
public void warmupOne(long activityId) {
    SeckillActivity act = activityMapper.selectById(activityId);
    require(act != null && act.getStatus() == ActivityStatus.READY.code(), "活动状态不允许预热");

    List<SeckillGoods> goodsList = goodsMapper.selectByActivity(activityId);
    require(!goodsList.isEmpty(), "活动无商品");

    for (SeckillGoods g : goodsList) {
        String tag = g.getActivityId() + ":" + g.getSkuId();
        String goodsKey = "seckill:goods:{" + tag + "}";
        String stockKey = "seckill:stock:{" + tag + "}";

        // 元数据可以无条件覆盖
        redis.opsForHash().putAll(goodsKey, Map.of(
                "status",       "1",
                "startTime",    String.valueOf(toMillis(act.getStartTime())),
                "endTime",      String.valueOf(toMillis(act.getEndTime())),
                "price",        g.getSeckillPrice().toPlainString(),
                "limitPerUser", String.valueOf(g.getLimitPerUser()),
                "totalStock",   String.valueOf(g.getTotalStock()),
                "version",      String.valueOf(act.getWarmupVersion() + 1)));
        redis.expire(goodsKey, ttlUntil(act.getEndTime()).plusHours(1));

        // 库存只在不存在时初始化。SETNX 语义，重复预热不会重置
        Boolean created = redis.opsForValue()
                .setIfAbsent(stockKey, String.valueOf(g.getAvailableStock()),
                             ttlUntil(act.getEndTime()).plusHours(1));
        if (Boolean.FALSE.equals(created)) {
            log.warn("库存已存在，跳过初始化 activityId={} skuId={} redisStock={} dbStock={}",
                     activityId, g.getSkuId(), redis.opsForValue().get(stockKey), g.getAvailableStock());
        }
    }
    activityMapper.markWarmupDone(activityId);   // status → READY, warmup_state → DONE, version+1
}
```

**`setIfAbsent` 而不是 `set` 是预热幂等的全部秘密。** 如果用 `set`，活动进行中
重跑一次预热（人为误操作、任务重复触发），Redis 库存被重置成 DB 的
`available_stock`，而 DB 的值又还没被消费端扣完，结果是超卖。

预热的状态校验要放宽到 **READY 或 RUNNING**（上面的代码只允许 READY）。
活动进行中重新预热是合法的运维动作：元数据被误改、Redis 主从切换后需要补数据
（故障用例 F2）。而正是这种场景让 `setIfAbsent` 变得不可或缺——
元数据可以无条件覆盖，库存绝不能跟着一起被重置。

预热任务捞活动时要带 `warmup_state IN (未预热, 预热失败)` 条件。不带的话任务每分钟
都会把同一批活动重新预热一遍，每次都打一行"库存已存在，跳过初始化"的 warn 日志——
真正的重复预热（人为误操作）就此淹没在噪音里，这条日志也就白打了。
带上这个条件还顺带让预热失败的活动被自动重试，不需要人工重新触发。

活动进行中要改库存，只能走管理接口 `POST /admin/goods/{id}/stock-adjust`，
该接口在同一事务内 `UPDATE t_seckill_goods` 并 `INCRBY` Redis，写审计日志。

## 9. 活动详情缓存：逻辑过期防击穿

热门活动的 `activity:detail:{id}` 在 TTL 到点时会有大量请求同时穿透到 DB。
用逻辑过期 + 单飞重建：

```java
public ActivityDetailVO getDetail(long activityId) {
    String key = "activity:detail:" + activityId;
    String json = redis.opsForValue().get(key);

    if (json == null) {
        // 缓存完全没有：可能是冷数据或空值缓存过期，加锁重建
        return rebuildWithLock(activityId, key);
    }

    CacheWrapper<ActivityDetailVO> w = JsonUtil.parse(json, ...);
    if (w.isNullValue()) {
        throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND);   // 空值缓存命中，防穿透
    }
    if (!w.logicallyExpired()) {
        return w.getData();
    }

    // 逻辑过期：先返回旧数据，后台单飞重建
    if (lock.tryLock("lock:cache:activity:" + activityId, 0, 10, SECONDS)) {
        cacheRebuildExecutor.submit(() -> {
            try { rebuild(activityId, key); }
            finally { lock.unlock("lock:cache:activity:" + activityId); }
        });
    }
    return w.getData();      // 短暂返回旧值，可接受
}
```

`CacheWrapper` 结构：

```java
record CacheWrapper<T>(T data, long expireAt, boolean nullValue) {
    boolean logicallyExpired() { return System.currentTimeMillis() > expireAt; }
}
```

物理 TTL = 逻辑过期时间 + 30 分钟缓冲，保证逻辑过期后旧值还在。

### 缓存里只能放静态骨架

`ActivityDetailVO` 里有三个字段**绝对不能跟着缓存一起冻住**，否则一个 2 小时 TTL 的
缓存会让演示现场一眼看出问题：

| 字段 | 处理方式 |
| --- | --- |
| `serverTime` | 每次读取时重算。客户端靠它校准倒计时，缓存住它，所有客户端的倒计时都停在缓存写入那一刻 |
| `status` | 由时间窗口**推导**（`now < start` → 待开始，`< end` → 进行中，否则已结束），不读 DB。这三级迁移完全由时间决定，推导结果与定时任务写进 DB 的一致，却不需要一次数据库往返 |
| `remainStock` | 取 Redis 实时库存，Redis 无 key 时退回缓存里的 DB 快照。异步化之后 DB 的 `available_stock` 会滞后于真实可抢量 |

管理员显式关闭（`CLOSED`）无法从时间推导，所以 `close` / `publish` / `stock-adjust`
这三个管理操作要**在事务提交后**主动清缓存。提交前清会留一个几毫秒的窗口：
删除与提交之间进来的读请求回源读到旧数据（它看不到未提交的新值）并写回缓存，
于是缓存里留下一份直到 TTL 到期都不会自愈的脏数据。

`ActivityDetailVO` 还必须有无参构造：它要被 Jackson 从 Redis 反序列化回来，
只有 `@Builder + @AllArgsConstructor` 时会报 "no Creators"——而这个错误发生在
缓存**回读**时，第一次写入完全正常，几分钟后才炸。

### 三类缓存问题的对应手段

| 问题 | 手段 |
| --- | --- |
| 穿透（查不存在的数据） | 参数校验（ID 必须为正）→ 布隆过滤器（活动 ID 集合，预热时构建）→ 空值缓存 60s |
| 击穿（热点 key 过期） | 活动前主动预热 + 逻辑过期 + 单飞重建 |
| 雪崩（大量 key 同时过期） | TTL 加 0~10 分钟随机抖动 + 分批预热 + Redis 主从哨兵 |

TTL 抖动实现：

```java
Duration ttl = props.getSeckill().getCacheTtl()
        .plusSeconds(ThreadLocalRandom.current()
                .nextInt((int) props.getSeckill().getCacheTtlJitter().toSeconds()));
```

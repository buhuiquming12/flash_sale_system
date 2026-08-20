-- =====================================================================
-- 脚本 A：秒杀资格判定与预扣
--
-- 一次调用完成六件事：请求幂等、元数据校验、时间窗口校验、一人一单校验、
-- 库存校验与预扣、写请求记录。这是整个系统唯一的资格分配入口。
--
-- KEYS[1] seckill:goods:{a:s}
-- KEYS[2] seckill:stock:{a:s}
-- KEYS[3] seckill:bought:{a:s}
-- KEYS[4] seckill:req:{a:s}:<requestNo>
-- ARGV[1] userId
-- ARGV[2] quantity
-- ARGV[3] requestNo
-- ARGV[4] traceId
-- ARGV[5] 请求结果 TTL（秒）
--
-- 返回 { code, remainStock }
--    0  成功预扣
--   -1  活动/商品未预热或不存在
--   -2  商品已被管理员停售
--   -3  活动未开始
--   -4  活动已结束
--   -5  已达限购（一人一单）
--   -6  库存不足
--   -7  请求号重复（同一 requestNo 已处理过）
--   -8  已售罄（预扣归零时打的快速失败标记）
-- =====================================================================

local goodsKey  = KEYS[1]
local stockKey  = KEYS[2]
local boughtKey = KEYS[3]
local reqKey    = KEYS[4]

local userId    = ARGV[1]
local qty       = tonumber(ARGV[2])
local traceId   = ARGV[4]
local reqTtl    = tonumber(ARGV[5])

-- 0. 请求号幂等：同一 requestNo 重放直接返回，绝不重复扣减。
--    阶段二 requestNo 由服务端每次新生成，走不到这里；阶段三 MQ 重投时它是第一道闸。
if redis.call('EXISTS', reqKey) == 1 then
    return { -7, tonumber(redis.call('GET', stockKey) or '0') }
end

-- 1. 元数据必须已预热。没预热就放行等于让 Redis 凭空造出库存
if redis.call('EXISTS', goodsKey) == 0 then
    return { -1, 0 }
end

local meta = redis.call('HMGET', goodsKey, 'status', 'startTime', 'endTime', 'limitPerUser')
local status       = tonumber(meta[1] or '-1')
local startTime    = tonumber(meta[2] or '0')
local endTime      = tonumber(meta[3] or '0')
local limitPerUser = tonumber(meta[4] or '1')

-- 售罄时预扣脚本把 status 改成 2，这里第二步就返回，脚本操作数从 5 降到 2。
-- 热点活动售罄后仍有大量请求涌入，这个短路能显著降低 Redis CPU。
--
-- 售罄(2) 与管理员停售(0) 必须返回<b>不同</b>的码：设计文档把两者合并成一个 -2，
-- 于是库存卖光之后用户看到的提示是"商品已停售"——文案错了，客服会收到一堆
-- "明明还有货为什么下架了"的工单。多一次整数比较换一条正确的错误码，划算
if status == 2 then
    return { -8, 0 }
end
if status ~= 1 then
    return { -2, 0 }
end

-- 2. 时间窗口用 Redis 自己的时钟，不用应用侧时钟。
--    多实例部署时某台机器时钟快 5 分钟就会提前放行请求（故障用例 F14），
--    而且「应用层判时间、Redis 判库存」之间还有一个窗口，
--    活动结束那一瞬间仍可能放行。判定与扣减在同一段 Lua 内才没有窗口
local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

if now < startTime then
    return { -3, 0 }
end
if now >= endTime then
    return { -4, 0 }
end

-- 3. 一人一单必须在扣库存之前。
--    反过来的话，同一用户重复提交会先扣掉库存再发现重复，需要在脚本里补回 DECRBY；
--    虽然补得回来，但一旦代码遗漏就是库存永久泄漏。先判重复从结构上消除这种可能
local bought = tonumber(redis.call('HGET', boughtKey, userId) or '0')
if bought + qty > limitPerUser then
    return { -5, 0 }
end

-- 4. 库存校验
local stock = tonumber(redis.call('GET', stockKey) or '0')
if stock < qty then
    return { -6, 0 }
end

-- 5. 预扣 + 标记 + 写请求记录。三者在同一段脚本内，对外原子可见：
--    不存在「库存扣了但用户标记没写」这种中间态被其他请求观察到的可能
local remain = redis.call('DECRBY', stockKey, qty)
redis.call('HINCRBY', boughtKey, userId, qty)

-- bought Hash 由 HINCRBY 惰性创建，<b>它没有 TTL</b>——设计文档的脚本漏了这一步，
-- 照写会让每场活动的购买标记永久驻留内存（10 万人一场就是几 MB，活动做多了必然撑爆，
-- 而 maxmemory-policy 是 noeviction，撑爆意味着写入直接失败）。
-- TTL 直接用脚本里已经读到的 endTime 算，不必再从 Java 传一个参数进来，
-- 也不会出现「Java 传的 TTL 和 Redis 里的 endTime 不一致」
local boughtTtl = math.floor((endTime - now) / 1000) + 86400
if boughtTtl < 86400 then
    boughtTtl = 86400
end
redis.call('EXPIRE', boughtKey, boughtTtl)

-- DECRBY 返回扣减后的值，一次调用同时完成写入和读取剩余量；
-- 用 SET 还得再 GET 一次
if remain <= 0 then
    redis.call('HSET', goodsKey, 'status', 2)
end

redis.call('HSET', reqKey,
        'status',  0,
        'userId',  userId,
        'traceId', traceId,
        'ts',      now)
redis.call('EXPIRE', reqKey, reqTtl)

return { 0, remain }

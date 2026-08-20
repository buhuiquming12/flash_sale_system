-- =====================================================================
-- 脚本 B：补偿回补（ROLLBACK 语义）
--
-- 用于「Redis 预扣成功但订单最终没能创建」。归还库存，并按 keepBought
-- 决定是否归还用户购买资格。
--
-- KEYS[1] seckill:stock:{a:s}
-- KEYS[2] seckill:bought:{a:s}
-- KEYS[3] seckill:req:{a:s}:<requestNo>
-- KEYS[4] seckill:goods:{a:s}
-- ARGV[1] userId
-- ARGV[2] quantity
-- ARGV[3] reason
-- ARGV[4] keepBought：1 = 保留用户购买标记，0 = 归还
-- ARGV[5] 请求结果 TTL（秒）
--
-- 返回 0 = 已回补   1 = 状态不允许回补（幂等命中，未做任何修改）
-- =====================================================================

local stockKey  = KEYS[1]
local boughtKey = KEYS[2]
local reqKey    = KEYS[3]
local goodsKey  = KEYS[4]

local userId     = ARGV[1]
local qty        = tonumber(ARGV[2])
local reason     = ARGV[3]
local keepBought = tonumber(ARGV[4]) == 1
local reqTtl     = tonumber(ARGV[5])

-- 幂等靠请求状态机而不是计数器：
-- 只有「排队中(0)」的请求可以回补。已成功(1)不能回补，已补偿(5)不能重复回补，
-- 失败态(2/3)本就没扣过库存。无论回补消息重复投递多少次，INCRBY 只执行一次
if redis.call('EXISTS', reqKey) == 0 then
    return 1
end
if tonumber(redis.call('HGET', reqKey, 'status') or '-1') ~= 0 then
    return 1
end

-- 先改状态，把自己变成唯一执行者。Lua 的原子性保证中间不存在
-- 「status 仍是 0 但库存已经加回」这种能被其他脚本观察到的状态
redis.call('HSET', reqKey, 'status', 5, 'reason', reason)
redis.call('EXPIRE', reqKey, reqTtl)

redis.call('INCRBY', stockKey, qty)

-- keepBought 是设计文档里没有的一个参数，但少了它会出死循环：
-- 落库失败原因是 ALREADY_BOUGHT（DB 侧 uk_activity_sku_user 冲突，
-- 通常因为 Redis 的 bought 标记丢了而 DB 里那张已取消的订单还占着唯一键）时，
-- 如果把资格也还给用户，用户重抢 → Redis 放行 → DB 又冲突 → 又回补，
-- 无限循环。这种确定性失败必须只还库存、不还资格
if not keepBought then
    local left = redis.call('HINCRBY', boughtKey, userId, -qty)
    if left <= 0 then
        redis.call('HDEL', boughtKey, userId)
    end
end

-- 库存从 0 恢复，取消售罄标记。注意只在 status = 2（售罄）时改回 1，
-- 被管理员停售的 0 必须保持停售
if tonumber(redis.call('HGET', goodsKey, 'status') or '1') == 2 then
    redis.call('HSET', goodsKey, 'status', 1)
end

return 0

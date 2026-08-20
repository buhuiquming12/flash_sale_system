-- =====================================================================
-- 脚本 C：取消回补（RELEASE 语义）
--
-- 用于订单超时关闭、用户主动取消。只归还库存，<b>保留</b>用户购买标记。
--
-- KEYS[1] seckill:stock:{a:s}
-- KEYS[2] seckill:released:{a:s}
-- KEYS[3] seckill:goods:{a:s}
-- ARGV[1] orderNo
-- ARGV[2] quantity
-- ARGV[3] released Set 的 TTL（秒）
--
-- 返回 0 = 已回补   1 = 该订单已回补过（幂等命中）
-- =====================================================================

local stockKey    = KEYS[1]
local releasedKey = KEYS[2]
local goodsKey    = KEYS[3]

local orderNo = ARGV[1]
local qty     = tonumber(ARGV[2])
local ttl     = tonumber(ARGV[3])

-- SADD 返回 0 表示成员已存在，天然幂等。
-- 这一层的意义是防「DB 事务已提交但 Redis 回补重试」——DB 侧的 stock_released
-- 条件更新只能保证 DB 不重复回补，管不住 Redis
if redis.call('SADD', releasedKey, orderNo) == 0 then
    return 1
end
redis.call('EXPIRE', releasedKey, ttl)

redis.call('INCRBY', stockKey, qty)

if tonumber(redis.call('HGET', goodsKey, 'status') or '1') == 2 then
    redis.call('HSET', goodsKey, 'status', 1)
end

-- 不动 seckill:bought。取消后不允许重抢（决策 1）：
-- 用户资格已消耗，库存回池给其他用户。
-- 动了 bought 就会出现「用户重抢 → Redis 放行 → DB 撞 uk_activity_sku_user
-- （已取消的订单仍占着唯一键）→ 回补 → 用户再抢」的死循环

return 0

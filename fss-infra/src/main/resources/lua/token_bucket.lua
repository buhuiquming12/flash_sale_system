-- =====================================================================
-- 脚本 E：令牌桶限流
--
-- 比固定窗口计数器好在没有窗口边界的双倍突发，比滑动窗口日志省内存。
--
-- KEYS[1] 桶 key
-- ARGV[1] 容量
-- ARGV[2] 每秒填充速率
-- ARGV[3] 本次请求令牌数
--
-- 返回 1 = 放行   0 = 拒绝
-- =====================================================================

local key      = KEYS[1]
local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])
local need     = tonumber(ARGV[3])

-- 用 Redis 时钟，多实例才有统一的时间基准
local t   = redis.call('TIME')
local now = tonumber(t[1]) + tonumber(t[2]) / 1000000

local bucket = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local lastTs = tonumber(bucket[2])

if tokens == nil or lastTs == nil then
    tokens = capacity
    lastTs = now
else
    local delta = now - lastTs
    if delta < 0 then
        delta = 0                      -- 时钟回拨时不倒扣，也不凭空补
    end
    tokens = math.min(capacity, tokens + delta * rate)
    lastTs = now
end

local allowed = 0
if tokens >= need then
    tokens  = tokens - need
    allowed = 1
end

-- 显式 tostring：tokens 与 ts 都是小数，必须原样存回。
-- 不同 Redis 版本把 Lua number 传给 redis.call 时用过 %.14g / %.17g 等不同格式，
-- 依赖隐式转换的精度不是好主意——ts 一旦被截断到整秒，
-- 下一次算出的 delta 最多会多出 1 秒，等于每次调用白送一秒的令牌，
-- 限流器就漏成筛子了
redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(lastTs))
redis.call('EXPIRE', key, math.ceil(capacity / rate) + 2)

return allowed

-- =====================================================================
-- 脚本 D：请求结果写入
--
-- 处理完成后回写结论，供客户端轮询。用条件写避免覆盖终态。
--
-- KEYS[1] seckill:req:{a:s}:<requestNo>
-- ARGV[1] newStatus
-- ARGV[2] orderNo（可为空串）
-- ARGV[3] reason （可为空串）
-- ARGV[4] TTL（秒）
-- ARGV[5] userId
--
-- 返回 0 = 写入成功   1 = 已是终态，不覆盖
-- =====================================================================

local reqKey = KEYS[1]
local ttl    = tonumber(ARGV[4])
local userId = ARGV[5]

-- userId 必须一起写。查询接口读到结果后要做归属校验
-- （不能让 A 查到 B 的秒杀结论），没有这个字段的话校验一定失败，
-- 表现是"明明失败了却查不到失败原因"，客户端只能一直轮询到超时。
--
-- 被 Lua 在时间/库存校验阶段拒绝的请求走的正是这条 EXISTS = 0 的分支：
-- 那些路径 return 得早，根本没创建过 req key，userId 只能由这里补上
if redis.call('EXISTS', reqKey) == 0 then
    redis.call('HSET', reqKey,
            'status',  ARGV[1],
            'orderNo', ARGV[2],
            'reason',  ARGV[3],
            'userId',  userId)
    redis.call('EXPIRE', reqKey, ttl)
    return 0
end

-- 只有「排队中(0)」可以被推进。终态不允许被覆盖——否则一条迟到的重试消息
-- 会把已经成功的结论改成失败
if tonumber(redis.call('HGET', reqKey, 'status') or '-1') ~= 0 then
    return 1
end

redis.call('HSET', reqKey,
        'status',  ARGV[1],
        'orderNo', ARGV[2],
        'reason',  ARGV[3],
        'userId',  userId)
redis.call('EXPIRE', reqKey, ttl)
return 0

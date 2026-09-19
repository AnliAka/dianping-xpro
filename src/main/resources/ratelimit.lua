-- 秒杀用户／活动滑动窗口频控（双层限流第二层）。
--
-- 调用方：SeckillRateLimiter，登录校验之后、发送半消息（预占）之前。
-- 键为 seckill:rate:{voucherId}:{userId}，类型为 ZSET：member＝唯一请求标识，score＝请求时刻(ms)。
--
-- 语义：窗口内「所有到达的请求」都被记录（含被拒请求），按 ZCARD 计数；
-- 超过限额即拒绝。相比只记录放行请求，脚本式重复请求会持续顶满窗口，
-- 无法在每个窗口边缘稳定拿到配额，更符合频控目的。
--
-- 返回值：1 = 放行；0 = 超过限额，拒绝。
-- Redis 单线程执行脚本，清理、计数、记录、续期四步对并发请求不可分割。

local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local member = ARGV[4]

-- 1. 原子清理：移除窗口外的历史请求。
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

-- 2. 计数：当前窗口内的请求数（不含本次）。
local count = redis.call('ZCARD', key)

-- 3. 记录本次请求：无论放行与否都写入，保持窗口对真实到达率的记忆。
redis.call('ZADD', key, now, member)

-- 4. 续期：活跃用户的键不过期，静默用户的键在窗口后自动清除。
redis.call('PEXPIRE', key, window + 1000)

if count >= limit then
    return 0
end
return 1

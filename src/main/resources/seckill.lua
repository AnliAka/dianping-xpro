-- 秒杀预占脚本（入口本地事务）。
--
-- 调用方：RocketMQ 事务监听器的 executeLocalTransaction，半消息发出后同步执行。
-- 返回值：
--   0 = 预占成功，调用方应 COMMIT
--   1 = 库存不足，调用方应 ROLLBACK
--   2 = 重复购买，调用方应 ROLLBACK
--   3 = 该 orderId 此前已被明确拒绝，调用方应 ROLLBACK
--
-- 两条必须守住的约束：
--   1. Redis 单线程执行脚本，脚本内部不会被打断，不存在"预占执行到一半被回查读到"的中间态。
--   2. 但 Lua 运行时报错不会回滚已执行的写入。故所有校验前置、写 SUCCESS 置于最后一个写操作，
--      使其成为最不可能失败的一步。执行异常必须由调用方按 UNKNOWN 处理，不得当作业务拒绝。
--
-- 本脚本中的 'SUCCESS' / 'REJECTED' 字面量须与 RedisConstants 中的同名常量保持一致。

local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local ttlSeconds = tonumber(ARGV[4])

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local txKey = 'seckill:tx:' .. orderId

-- 第 0 步：幂等。同一 orderId 可能因半消息重发而被执行多次。
-- 必须先于库存与一人一单校验：否则第二次执行会命中"已购买"而写入 REJECTED，
-- 把第一次写下的 SUCCESS 覆盖掉，等于把已经成功的预占反向回滚。
local tx = redis.call('get', txKey)
if tx == 'SUCCESS' then
    return 0
end
if tx == 'REJECTED' then
    return 3
end

-- 第 1 步：库存校验。get 在 stockKey 类型异常时即报错，此时尚无副作用。
local stock = redis.call('get', stockKey)
if stock == false or tonumber(stock) <= 0 then
    redis.call('set', txKey, 'REJECTED', 'EX', ttlSeconds)
    return 1
end

-- 第 2 步：一人一单校验。orderKey 为 HASH：userId -> orderId。
if redis.call('hexists', orderKey, userId) == 1 then
    redis.call('set', txKey, 'REJECTED', 'EX', ttlSeconds)
    return 2
end

-- 第 3 步：预占。写 SUCCESS 必须在扣减与映射之后。
redis.call('incrby', stockKey, -1)
redis.call('hset', orderKey, userId, orderId)
redis.call('set', txKey, 'SUCCESS', 'EX', ttlSeconds)

return 0

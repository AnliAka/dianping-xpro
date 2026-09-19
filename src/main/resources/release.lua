-- 释放秒杀预占脚本（关单后消费）。
--
-- 调用方：预占释放消费者（StockReleaseConsumer），关单事务提交后收到消息执行。
-- ARGV：voucherId, userId, orderId（与 seckill.lua 的键拼接规则保持一致）
-- 返回值：
--   1 = 已释放（删除本订单的一人一单映射并回加库存）
--   0 = 无需释放：映射不存在（重复投递）或已经指向另一张新订单
--
-- 幂等依据：seckill:order:{voucherId} 是 HASH：userId -> orderId。
-- 只有当前值等于本订单 id 才删除并回加库存。重复投递时映射已不存在；
-- 用户重新下单后映射会指向新 orderId，旧释放消息也不能误删新预占。
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local orderKey = 'seckill:order:' .. voucherId
local stockKey = 'seckill:stock:' .. voucherId

local reserved = redis.call('hget', orderKey, userId)
if reserved ~= orderId then
    return 0
end

redis.call('hdel', orderKey, userId)
-- 库存键不存在说明 Redis 侧数据已丢失/被清理，此时 incrby 会凭空造出 stock=1，
-- 之后预热（setIfAbsent）不会覆盖它，等于凭空多出一张库存，所以只在键存在时回加。
-- 该场景下的库存核算由预热重建，不依赖此处补偿。
if redis.call('exists', stockKey) == 1 then
    redis.call('incrby', stockKey, 1)
end
return 1

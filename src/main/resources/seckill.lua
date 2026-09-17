local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local streamKey = 'stream.orders'

local stock = redis.call('get', stockKey)
if(stock == false or tonumber(stock) <= 0) then
    return 1
end
if(redis.call('sismember',orderKey,userId) == 1) then
    return 2
end

redis.call('incrby',stockKey,-1)

redis.call('sadd',orderKey,userId)

redis.call('xadd',streamKey,'*','id',orderId,'userId',userId,'voucherId',voucherId)

return 0

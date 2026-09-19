-- 网关计数查询：返回令牌桶三态计数，压测期间轮询 GET /gateway/stats 核对网关拒绝率。
-- 计数存于 lua_shared_dict，仅进程内存级别：网关重启即清零，跨轮压测需按轮读取。

local function show()
    local stats = ngx.shared.seckill_stats
    ngx.header.content_type = "application/json;charset=UTF-8"
    ngx.say(string.format('{"total":%d,"passed":%d,"rejected":%d}',
            stats:get("total") or 0,
            stats:get("passed") or 0,
            stats:get("rejected") or 0))
end

return { show = show }

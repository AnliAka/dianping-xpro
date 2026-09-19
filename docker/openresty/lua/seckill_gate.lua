-- 秒杀入口门卫：只对 POST /voucher-order/seckill/{voucherId} 施加全局令牌桶。
-- 这是双层限流的第一层，拒绝发生在请求到达应用（预占）之前。
--
-- 计数口径：total 统计到达网关的秒杀请求；passed 为放行；rejected 为令牌不足被拒。
-- 非 POST（如误用 GET 探测路径）不计入 total，直接放行由应用按 405 处理。

local token_bucket = require("token_bucket")

local function flag(name, default)
    local v = os.getenv(name)
    if v == nil then
        return default
    end
    return v == "true" or v == "1"
end

-- shm:incr 对不存在的键返回 not found，必须带 init 从 0 起计。
local function incr(stats, name)
    stats:incr(name, 1, 0)
end

local function _run()
    local stats = ngx.shared.seckill_stats
    if ngx.req.get_method() ~= "POST" then
        return
    end

    incr(stats, "total")

    -- 关闭开关时仍计数但放行：压测对照组 A「网关仅转发」即此状态，rejected 恒为 0。
    if not flag("SECKILL_GATEWAY_ENABLED", true) then
        incr(stats, "passed")
        return
    end

    local rate = tonumber(os.getenv("SECKILL_TOKEN_RATE")) or 500
    local burst = tonumber(os.getenv("SECKILL_TOKEN_BURST")) or 1000
    if rate <= 0 then
        incr(stats, "passed")
        return
    end

    local allowed = token_bucket.take(ngx.shared.seckill_bucket, rate, burst)
    if allowed then
        incr(stats, "passed")
        return
    end

    incr(stats, "rejected")
    ngx.var.gw_reject = "1"
    -- 429 与应用层业务失败（HTTP 200 + success=false）区分开，
    -- 压测里据此分别统计网关拒绝率与用户拒绝率。
    ngx.status = 429
    ngx.header.content_type = "application/json;charset=UTF-8"
    ngx.say('{"success":false,"errorMsg":"当前排队人数过多，请稍后再试"}')
    return ngx.exit(ngx.HTTP_OK)
end

return { run = _run }

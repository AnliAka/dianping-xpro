-- 原子令牌桶：状态存放在 lua_shared_dict（跨 worker 共享），自旋锁保证「读-补-扣-写」的原子性。
--
-- 单节点部署下 worker 数有限、临界区只有几次 shm 读写，自旋几乎不会发生；
-- 锁带 TTL，持有者异常退出后最多阻塞 LOCK_TTL 秒即可自愈，不会死锁。
-- 拿不到锁按令牌不足处理（拒绝）：宁可多拒一条，也不放过无计数的请求。

local _M = {}

local STATE_KEY = "bucket:seckill"
local LOCK_KEY = "lock:seckill"
local LOCK_TTL = 0.05 -- 秒
local MAX_SPIN = 50

local function acquire_lock(shm)
    for _ = 1, MAX_SPIN do
        if shm:add(LOCK_KEY, true, LOCK_TTL) then
            return true
        end
        ngx.sleep(0.001)
    end
    return false
end

-- 取一个令牌。
-- 返回 allowed（true=放行）；rate 为令牌产生速率（个/秒），burst 为桶容量。
function _M.take(shm, rate, burst)
    if not acquire_lock(shm) then
        return false
    end

    local now_ms = ngx.now() * 1000
    local state = shm:get(STATE_KEY)
    local tokens, last_ms
    if state then
        local bar = state:find("|", 1, true)
        tokens = tonumber(state:sub(1, bar - 1))
        last_ms = tonumber(state:sub(bar + 1))
    else
        -- 初始满桶：开场允许一次完整的突发容量。
        tokens, last_ms = burst, now_ms
    end

    -- 懒补充：按距上次取令牌的耗时一次性补足，空闲期不产生任何开销。
    local refill = (now_ms - last_ms) / 1000 * rate
    if refill > 0 then
        tokens = math.min(burst, tokens + refill)
    end

    local allowed = tokens >= 1
    if allowed then
        tokens = tokens - 1
    end

    -- 持锁期间无条件覆盖；string.format 保证浮点 tokens 的定点表示稳定。
    shm:set(STATE_KEY, string.format("%.3f|%d", tokens, now_ms))
    shm:delete(LOCK_KEY)
    return allowed
end

return _M

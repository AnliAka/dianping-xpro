package com.dianping.xpro.ratelimit;

import com.dianping.xpro.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 秒杀用户／活动滑动窗口频控（双层限流第二层）。
 *
 * <p>位于登录校验之后、RocketMQ 半消息（Redis 预占）之前：被拒请求不会触发预占，
 * 也没有库存副作用。维度为可信 userId＋活动（券）ID，即同一用户对同一秒杀活动的
 * 请求频率，跨用户互不影响。</p>
 *
 * <p>Redis 不可达时放行（fail-open）：频控是保护层而非正确性来源，下游预占脚本与
 * 唯一约束仍兜底；且预占同样依赖 Redis，Redis 故障时本层拦截已无意义。</p>
 */
@Slf4j
@Component
public class SeckillRateLimiter {

    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>();

    static {
        SLIDING_WINDOW_SCRIPT.setLocation(new ClassPathResource("ratelimit.lua"));
        SLIDING_WINDOW_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    /** 是否启用第二层频控；false 时压测对照组 B（仅令牌桶）即此状态。 */
    @Value("${app.seckill.rate-limit.enabled:true}")
    private boolean enabled;

    /** 滑动窗口长度（毫秒）。占位值，待 T3 容量实验固定。 */
    @Value("${app.seckill.rate-limit.window-ms:2000}")
    private long windowMs;

    /** 窗口内单用户单活动允许的最大请求数。占位值，待 T3 容量实验固定。 */
    @Value("${app.seckill.rate-limit.max-requests:5}")
    private long maxRequests;

    public SeckillRateLimiter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 判断本次请求是否放行。member 用 nanoTime 保证同一毫秒内的多次请求可共存于 ZSET。
     */
    public boolean tryAcquire(Long voucherId, Long userId) {
        if (!enabled) {
            return true;
        }
        String key = RedisConstants.SECKILL_RATE_KEY + voucherId + ":" + userId;
        try {
            Long result = stringRedisTemplate.execute(SLIDING_WINDOW_SCRIPT,
                    List.of(key),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(windowMs),
                    String.valueOf(maxRequests),
                    Long.toString(System.nanoTime()));
            if (result == null) {
                log.warn("频控脚本无返回, 按放行处理, key={}", key);
                return true;
            }
            return result == 1L;
        } catch (Exception e) {
            log.error("频控脚本执行异常, 按放行处理, key={}", key, e);
            return true;
        }
    }
}

package com.dianping.xpro.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/**
 * 预占释放消费者。关单事务提交后收到消息，执行 release.lua 归还 Redis 预占：
 * 删除本订单的一人一单映射并回加库存。幂等由脚本内的 orderId 比对保证，
 * 用户在取消或超时后可以重新购买同一券。
 *
 * <p>Redis 不可用时抛出异常交由 MQ 重试，不得静默确认——否则预占泄漏，
 * 表现为库存少一张且用户无法重新购买。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.ORDER_STOCK_RELEASE_TOPIC,
        consumerGroup = MqConstants.ORDER_STOCK_RELEASE_CONSUMER_GROUP)
public class StockReleaseConsumer implements RocketMQListener<String> {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>();

    static {
        RELEASE_SCRIPT.setLocation(new ClassPathResource("release.lua"));
        RELEASE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String message) {
        StockReleaseMessage release;
        try {
            release = objectMapper.readValue(message, StockReleaseMessage.class);
        } catch (Exception e) {
            log.error("预占释放消息解析失败, message={}", message, e);
            throw new IllegalArgumentException("无法解析的预占释放消息", e);
        }

        Long result = stringRedisTemplate.execute(RELEASE_SCRIPT, new ArrayList<>(),
                release.voucherId().toString(),
                release.userId().toString(),
                release.orderId().toString());
        if (result == null) {
            throw new IllegalStateException("释放脚本无返回值, orderId=" + release.orderId());
        }
        if (result == 1L) {
            log.info("Redis 预占已释放：orderId={}, voucherId={}, userId={}",
                    release.orderId(), release.voucherId(), release.userId());
        } else {
            // 重复投递，或映射已经指向新的订单，无需释放。
            log.info("Redis 预占无需释放（幂等跳过）：orderId={}, voucherId={}, userId={}",
                    release.orderId(), release.voucherId(), release.userId());
        }
    }
}

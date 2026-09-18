package com.dianping.xpro.mq;

import com.dianping.xpro.utils.RedisConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀入口的事务消息监听器。
 *
 * <p>本地事务即 {@code seckill.lua} 的一次执行：校验库存与一人一单、扣减 Redis 库存、
 * 记录用户购买关系、写入事务结果。回查直接读 {@code seckill:tx:{orderId}}，不重新执行预占，
 * 也不依赖尚未创建的数据库订单。</p>
 *
 * <p>状态判定：Redis 中无记录或不可访问一律返回 UNKNOWN，交给 Broker 继续回查；
 * 只有明确写下的 REJECTED 才返回 ROLLBACK。执行异常绝不能当作业务拒绝——
 * 否则会把已经扣减的预占连消息一起丢掉。</p>
 */
@Slf4j
@Component
@RocketMQTransactionListener(rocketMQTemplateBeanName = "rocketMQTemplate")
public class OrderTransactionListener implements RocketMQLocalTransactionListener {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT = new DefaultRedisScript<>();

    static {
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final FaultInjector faultInjector;

    public OrderTransactionListener(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper,
                                    FaultInjector faultInjector) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.faultInjector = faultInjector;
    }

    /**
     * 本地事务：执行预占脚本。仅在返回 0（预占成功）时提交半消息。
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        SeckillOrderMessage order = parse(msg);
        if (order == null) {
            // 消息体不合法属于编码错误，没有可执行的预占，直接回滚。
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, new ArrayList<>(),
                    order.voucherId().toString(),
                    order.userId().toString(),
                    order.orderId().toString(),
                    String.valueOf(TimeUnit.HOURS.toSeconds(RedisConstants.SECKILL_TX_TTL)));
            if (result == null) {
                return RocketMQLocalTransactionState.UNKNOWN;
            }
            if (result == 0L) {
                // 测试用故障点：此刻预占已落 Redis，但还没把 COMMIT 送给 Broker。
                faultInjector.exitAfterReserve(order.orderId(), order.userId(), order.voucherId());
                return RocketMQLocalTransactionState.COMMIT;
            }
            // 1 库存不足 / 2 重复购买 / 3 该 orderId 已被拒绝，均为明确的业务拒绝。
            return RocketMQLocalTransactionState.ROLLBACK;
        } catch (Exception e) {
            // 脚本可能已产生部分写入（Lua 报错不回滚），状态未知，交由回查裁决。
            log.error("秒杀预占执行异常, orderId={}", order.orderId(), e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    /**
     * 回查：只解释已写入的事务结果，不重新扣减库存。
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        SeckillOrderMessage order = parse(msg);
        if (order == null) {
            return RocketMQLocalTransactionState.UNKNOWN;
        }
        try {
            String state = stringRedisTemplate.opsForValue()
                    .get(RedisConstants.SECKILL_TX_KEY + order.orderId());
            if (RedisConstants.SECKILL_TX_SUCCESS.equals(state)) {
                return RocketMQLocalTransactionState.COMMIT;
            }
            if (RedisConstants.SECKILL_TX_REJECTED.equals(state)) {
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            // 无记录：可能是预占尚未执行完（慢回调），也可能是结果键已过期或 Redis 数据丢失。
            // 本设计不主动写入终止标记，两种情况都交给回查次数上限收敛。
            return RocketMQLocalTransactionState.UNKNOWN;
        } catch (Exception e) {
            log.error("秒杀预占回查失败, orderId={}", order.orderId(), e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    private SeckillOrderMessage parse(Message<?> msg) {
        try {
            Object payload = msg.getPayload();
            byte[] body = payload instanceof byte[] bytes
                    ? bytes
                    : String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
            return objectMapper.readValue(body, SeckillOrderMessage.class);
        } catch (Exception e) {
            log.error("秒杀消息体解析失败", e);
            return null;
        }
    }
}

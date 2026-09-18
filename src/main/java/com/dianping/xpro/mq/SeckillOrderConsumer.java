package com.dianping.xpro.mq;

import com.dianping.xpro.service.IVoucherOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 秒杀建单消费者。消息已由事务消息机制保证"预占成功才会投递"，这里只负责幂等落单。
 *
 * <p>onMessage 正常返回即确认消费；抛出异常则交由 MQ 重试，超限后进入死信队列。
 * 数据库异常、库存扣减失败都必须抛出，不得静默确认。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.SECKILL_ORDER_TOPIC,
        consumerGroup = MqConstants.SECKILL_ORDER_CONSUMER_GROUP)
public class SeckillOrderConsumer implements RocketMQListener<String> {

    private final IVoucherOrderService voucherOrderService;
    private final ObjectMapper objectMapper;
    private final FaultInjector faultInjector;

    @Override
    public void onMessage(String message) {
        SeckillOrderMessage order;
        try {
            order = objectMapper.readValue(message, SeckillOrderMessage.class);
        } catch (Exception e) {
            log.error("秒杀建单消息解析失败, message={}", message, e);
            // 无法解析的消息重试也不会成功，抛出后由 MQ 的重试与死信机制收口。
            throw new IllegalArgumentException("无法解析的秒杀建单消息", e);
        }
        voucherOrderService.createVoucherOrder(order);
        // 测试用故障点：走到这里事务已经提交，但方法还没返回，消费尚未确认。
        // 必须放在 @Transactional 方法之外，方法内部的最后一行仍在事务里，命中不了这个窗口。
        faultInjector.exitAfterConsumeCommit(order.orderId(), order.voucherId());
    }
}

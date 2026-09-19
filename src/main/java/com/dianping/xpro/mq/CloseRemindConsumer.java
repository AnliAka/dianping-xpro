package com.dianping.xpro.mq;

import com.dianping.xpro.service.IOrderCloseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 关单提醒消费者。延迟消息只是"闹钟"：18 级延迟的粒度对不上任意截止时刻，
 * 所以消费时先对 pay_deadline 做绝对时间校验——
 * 未到期按剩余时间重投（收敛到到期），已到期才执行关单。
 *
 * <p>关单本身幂等：订单已被支付或取消时 closeOrder 返回 false，正常确认消费。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.ORDER_CLOSE_REMIND_TOPIC,
        consumerGroup = MqConstants.ORDER_CLOSE_REMIND_CONSUMER_GROUP)
public class CloseRemindConsumer implements RocketMQListener<String> {

    private final IOrderCloseService orderCloseService;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String message) {
        CloseRemindMessage remind;
        try {
            remind = objectMapper.readValue(message, CloseRemindMessage.class);
        } catch (Exception e) {
            log.error("关单提醒消息解析失败, message={}", message, e);
            // 无法解析的消息重试也不会成功，抛出交由重试与死信收口。
            throw new IllegalArgumentException("无法解析的关单提醒消息", e);
        }

        long remainingMillis = remind.payDeadlineMillis() - System.currentTimeMillis();
        if (remainingMillis > 0) {
            // 提醒早到（延迟级别只能覆盖、不能精确命中）：按剩余时间重投，本轮消费正常确认。
            int level = DelayLevels.forRemainingMillis(remainingMillis);
            log.info("关单提醒未到期，重投延迟消息：orderId={}, 剩余{}ms, 级别={}",
                    remind.orderId(), remainingMillis, level);
            rocketMQTemplate.syncSend(
                    MqConstants.ORDER_CLOSE_REMIND_TOPIC,
                    MessageBuilder.withPayload(message).build(),
                    3000, level);
            return;
        }

        boolean closed = orderCloseService.closeOrder(remind.orderId(), true);
        if (closed) {
            log.info("延迟提醒触发关单成功：orderId={}", remind.orderId());
        } else {
            // 已支付或已取消（用户取消先到），幂等放弃。
            log.info("延迟提醒到达但订单无需关单：orderId={}", remind.orderId());
        }
    }
}

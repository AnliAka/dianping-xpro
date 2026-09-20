package com.dianping.xpro.mq;

import com.dianping.xpro.utils.ShopLocalCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 每个应用实例独立消费本地失效通知。部署时必须为两个实例配置不同 consumerGroup，
 * 使同一通知分别投递到每个实例，而不是在实例间负载均衡。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.SHOP_LOCAL_INVALIDATION_TOPIC,
        consumerGroup = "${app.shop-cache.local-invalidation-consumer-group}")
public class ShopLocalInvalidationConsumer implements RocketMQListener<String> {

    private final ShopLocalCache shopLocalCache;
    private final ObjectMapper objectMapper;

    @Value("${app.instance-id}")
    private String instanceId;

    @Override
    public void onMessage(String message) {
        ShopLocalInvalidationMessage invalidation;
        try {
            invalidation = objectMapper.readValue(message, ShopLocalInvalidationMessage.class);
        } catch (Exception e) {
            log.error("商铺本地缓存失效消息解析失败, instanceId={}, message={}", instanceId, message, e);
            throw new IllegalArgumentException("无法解析商铺本地缓存失效消息", e);
        }
        if (invalidation.shopId() == null) {
            throw new IllegalArgumentException("商铺本地缓存失效消息缺少 shopId");
        }

        shopLocalCache.invalidate(invalidation.shopId());
        log.info("商铺本地缓存已失效, instanceId={}, shopId={}, sourceEventId={}",
                instanceId, invalidation.shopId(), invalidation.sourceEventId());
    }
}

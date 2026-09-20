package com.dianping.xpro.mq;

import com.dianping.xpro.utils.RedisLock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;

import static com.dianping.xpro.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.dianping.xpro.utils.RedisConstants.LOCK_SHOP_TTL;

/**
 * 消费 Canal 的 flat JSON 商铺变更事件，先在重建锁内删除 Redis，再投递本地缓存失效通知。
 *
 * <p>该消费组在多个应用实例间采用集群消费，同一事件只需删除一次共享 Redis。删除命令返回
 * {@code false} 代表键原本就不存在，同样达到失效目标；只有命令或通知发送抛出异常时才交给
 * RocketMQ 重试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.SHOP_BINLOG_TOPIC,
        consumerGroup = MqConstants.SHOP_REDIS_INVALIDATION_CONSUMER_GROUP)
public class ShopBinlogConsumer implements RocketMQListener<String> {

    private static final String SHOP_TABLE = "tb_shop";

    private final StringRedisTemplate stringRedisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String message) {
        JsonNode event = parseEvent(message);
        if (!SHOP_TABLE.equalsIgnoreCase(event.path("table").asText())) {
            return;
        }

        String operation = event.path("type").asText().toUpperCase(Locale.ROOT);
        if (!operation.equals("INSERT") && !operation.equals("UPDATE") && !operation.equals("DELETE")) {
            return;
        }

        JsonNode rows = event.path("data");
        if (!rows.isArray() || rows.isEmpty()) {
            throw new IllegalArgumentException("Canal 商铺事件缺少 data 行: " + message);
        }

        String canalEventId = event.path("id").asText(event.path("es").asText("unknown"));
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            JsonNode idNode = rows.get(rowIndex).path("id");
            if (!idNode.canConvertToLong() && !idNode.isTextual()) {
                throw new IllegalArgumentException("Canal 商铺事件缺少主键 id: " + message);
            }
            long shopId;
            try {
                shopId = idNode.asLong(Long.parseLong(idNode.asText()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Canal 商铺事件主键不是 Long: " + idNode, e);
            }
            invalidateRedisThenNotify(shopId, canalEventId + ":" + rowIndex);
        }
    }

    private JsonNode parseEvent(String message) {
        try {
            return objectMapper.readTree(message);
        } catch (Exception e) {
            log.error("Canal 商铺事件解析失败, message={}", message, e);
            throw new IllegalArgumentException("无法解析 Canal 商铺事件", e);
        }
    }

    private void invalidateRedisThenNotify(long shopId, String sourceEventId) {
        RedisLock rebuildLock = new RedisLock("shop:" + shopId, stringRedisTemplate);
        if (!rebuildLock.lock(LOCK_SHOP_TTL)) {
            throw new IllegalStateException("商铺正在重建，稍后重试失效事件, shopId=" + shopId);
        }

        try {
            Boolean existed = stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
            String notification = objectMapper.writeValueAsString(
                    new ShopLocalInvalidationMessage(shopId, sourceEventId));
            rocketMQTemplate.syncSend(MqConstants.SHOP_LOCAL_INVALIDATION_TOPIC, notification);
            log.info("商铺 Redis 已失效并通知各实例, shopId={}, existed={}, sourceEventId={}",
                    shopId, existed, sourceEventId);
        } catch (Exception e) {
            throw new IllegalStateException("商铺缓存失效链路执行失败, shopId=" + shopId, e);
        } finally {
            rebuildLock.unlock();
        }
    }
}

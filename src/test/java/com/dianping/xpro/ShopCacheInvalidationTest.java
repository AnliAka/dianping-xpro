package com.dianping.xpro;

import com.dianping.xpro.entity.Shop;
import com.dianping.xpro.mq.MqConstants;
import com.dianping.xpro.mq.ShopBinlogConsumer;
import com.dianping.xpro.mq.ShopLocalInvalidationConsumer;
import com.dianping.xpro.mq.ShopLocalInvalidationMessage;
import com.dianping.xpro.utils.ShopLocalCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopCacheInvalidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void disabledLocalLayerKeepsTheCorrectedRedisBaselineWithoutCaching() {
        ShopLocalCache localCache = new ShopLocalCache(false);
        AtomicInteger loads = new AtomicInteger();

        localCache.getOrLoad(1L, () -> new Shop().setId((long) loads.incrementAndGet()));
        localCache.getOrLoad(1L, () -> new Shop().setId((long) loads.incrementAndGet()));

        assertThat(loads).hasValue(2);
        assertThat(localCache.getIfPresent(1L)).isNull();
    }

    @Test
    void invalidationWinsWhenAnOlderLocalLoadIsStillRunning() throws Exception {
        ShopLocalCache localCache = new ShopLocalCache();
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch allowLoadToFinish = new CountDownLatch(1);
        CountDownLatch invalidationStarted = new CountDownLatch(1);
        Shop staleShop = new Shop().setId(1L).setName("旧值");

        try (var executor = Executors.newFixedThreadPool(2)) {
            var load = executor.submit(() -> localCache.getOrLoad(1L, () -> {
                loadStarted.countDown();
                try {
                    allowLoadToFinish.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return staleShop;
            }));

            assertThat(loadStarted.await(2, TimeUnit.SECONDS)).isTrue();
            var invalidate = executor.submit(() -> {
                invalidationStarted.countDown();
                localCache.invalidate(1L);
            });
            assertThat(invalidationStarted.await(2, TimeUnit.SECONDS)).isTrue();

            allowLoadToFinish.countDown();
            assertThat(load.get(2, TimeUnit.SECONDS)).isSameAs(staleShop);
            invalidate.get(2, TimeUnit.SECONDS);
        }

        assertThat(localCache.getIfPresent(1L))
                .as("失效必须排在旧加载回填之后，最终不能留下旧值")
                .isNull();
    }

    @Test
    void canalEventDeletesRedisBeforePublishingIdOnlyNotification() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        ShopBinlogConsumer consumer = new ShopBinlogConsumer(redis, rocketMQTemplate, objectMapper);
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq("lock:shop:8"), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(redis.delete("cache:shop:8")).thenReturn(true);

        consumer.onMessage("""
                {"id":123,"database":"dianping_xpro","table":"tb_shop","type":"UPDATE",
                 "data":[{"id":"8","name":"新名称"}],"old":[{"name":"旧名称"}]}
                """);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(redis, rocketMQTemplate);
        order.verify(redis).delete("cache:shop:8");
        order.verify(rocketMQTemplate).syncSend(
                eq(MqConstants.SHOP_LOCAL_INVALIDATION_TOPIC), payload.capture());
        verify(redis).execute(any(), anyList(), any());

        ShopLocalInvalidationMessage notification =
                objectMapper.readValue(payload.getValue(), ShopLocalInvalidationMessage.class);
        assertThat(notification.shopId()).isEqualTo(8L);
        assertThat(notification.sourceEventId()).isEqualTo("123:0");
        assertThat(payload.getValue()).doesNotContain("新名称", "旧名称", "name");
    }

    @Test
    void redisFailureDoesNotPublishLocalInvalidation() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        ShopBinlogConsumer consumer = new ShopBinlogConsumer(redis, rocketMQTemplate, objectMapper);
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq("lock:shop:8"), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(redis.delete("cache:shop:8")).thenThrow(new IllegalStateException("redis down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.onMessage("""
                {"id":124,"table":"tb_shop","type":"UPDATE","data":[{"id":"8"}]}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("失效链路执行失败");

        verify(rocketMQTemplate, never()).syncSend(anyString(), anyString());
    }

    @Test
    void eachInstanceConsumerInvalidatesItsOwnLocalCache() throws Exception {
        ShopLocalCache firstInstanceCache = new ShopLocalCache();
        ShopLocalCache secondInstanceCache = new ShopLocalCache();
        firstInstanceCache.getOrLoad(9L, () -> new Shop().setId(9L).setName("旧值"));
        secondInstanceCache.getOrLoad(9L, () -> new Shop().setId(9L).setName("旧值"));
        String message = objectMapper.writeValueAsString(new ShopLocalInvalidationMessage(9L, "125:0"));

        ShopLocalInvalidationConsumer first =
                new ShopLocalInvalidationConsumer(firstInstanceCache, objectMapper);
        ShopLocalInvalidationConsumer second =
                new ShopLocalInvalidationConsumer(secondInstanceCache, objectMapper);
        ReflectionTestUtils.setField(first, "instanceId", "app-1");
        ReflectionTestUtils.setField(second, "instanceId", "app-2");

        first.onMessage(message);
        second.onMessage(message);

        assertThat(firstInstanceCache.getIfPresent(9L)).isNull();
        assertThat(secondInstanceCache.getIfPresent(9L)).isNull();
    }
}

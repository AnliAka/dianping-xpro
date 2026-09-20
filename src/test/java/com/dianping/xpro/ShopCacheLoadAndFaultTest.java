package com.dianping.xpro;

import com.dianping.xpro.dto.Result;
import com.dianping.xpro.entity.Shop;
import com.dianping.xpro.mapper.ShopMapper;
import com.dianping.xpro.mq.MqConstants;
import com.dianping.xpro.mq.ShopBinlogConsumer;
import com.dianping.xpro.mq.ShopLocalInvalidationConsumer;
import com.dianping.xpro.service.impl.ShopServiceImpl;
import com.dianping.xpro.utils.ShopLocalCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商铺双层缓存最关键的容量与故障窗口验证。
 *
 * <p>这不是替代真实网络压测的微基准，而是可在 CI 重复执行的并发验收：热点流量不得穿透到
 * Redis/MySQL；提交后首次删除若被旧加载覆盖，Canal 事件驱动的延迟二次删除必须最终清掉
 * Redis 和两个实例的旧本地值。</p>
 */
class ShopCacheLoadAndFaultTest {

    private static final int WORKER_COUNT = 32;
    private static final int REQUESTS_PER_WORKER = 1_000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void hotShopConcurrencyDoesNotPenetrateTheLocalCache() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ShopMapper shopMapper = mock(ShopMapper.class);
        Shop shop = new Shop().setId(1L).setName("热点商铺");
        ShopServiceImpl service = serviceWith(redis, shopMapper, new ShopLocalCache());

        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("cache:shop:1")).thenReturn(null);
        when(valueOperations.setIfAbsent(
                eq("lock:shop:1"), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(shopMapper.selectById(1L)).thenReturn(shop);

        // 先完成一次冷启动重建；后续并发必须全部命中本地层。
        assertThat(service.queryById(1L).getSuccess()).isTrue();

        CountDownLatch ready = new CountDownLatch(WORKER_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> workers = new ArrayList<>(WORKER_COUNT);
        try (var executor = Executors.newFixedThreadPool(WORKER_COUNT)) {
            for (int worker = 0; worker < WORKER_COUNT; worker++) {
                workers.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    int successful = 0;
                    for (int request = 0; request < REQUESTS_PER_WORKER; request++) {
                        Result result = service.queryById(1L);
                        if (Boolean.TRUE.equals(result.getSuccess()) && result.getData() == shop) {
                            successful++;
                        }
                    }
                    return successful;
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successful = 0;
            for (Future<Integer> worker : workers) {
                successful += worker.get(15, TimeUnit.SECONDS);
            }
            assertThat(successful).isEqualTo(WORKER_COUNT * REQUESTS_PER_WORKER);
        }

        // 冷启动仅允许：Redis 初查 + 获锁后二次检查 + 一次 MySQL 重建。
        verify(valueOperations, times(2)).get("cache:shop:1");
        verify(shopMapper, times(1)).selectById(1L);
    }

    @Test
    void canalSecondDeleteRemovesStaleValueWrittenAfterCommitDelete() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        ShopMapper shopMapper = mock(ShopMapper.class);
        ShopServiceImpl shopService = serviceWith(redis, shopMapper, new ShopLocalCache());
        ShopBinlogConsumer binlogConsumer =
                new ShopBinlogConsumer(redis, rocketMQTemplate, objectMapper);
        AtomicReference<String> redisValue = new AtomicReference<>("old-shop-json");
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq("lock:shop:9"), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(redis.delete("cache:shop:9"))
                .thenAnswer(invocation -> redisValue.getAndSet(null) != null);
        when(rocketMQTemplate.syncSend(
                eq(MqConstants.SHOP_LOCAL_INVALIDATION_TOPIC), anyString()))
                .thenReturn(mock(SendResult.class));
        when(shopMapper.updateById(org.mockito.ArgumentMatchers.any(Shop.class))).thenReturn(1);

        // 第一次删除：数据库事务提交后立即删除 Redis。
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(shopService.updateShop(new Shop().setId(9L).setName("新值")).getSuccess())
                    .isTrue();
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.getFirst().afterCommit();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(redisValue).hasValue(null);

        // 故障窗口：提交前已经读到旧数据库值的慢加载，在第一次删除之后才回填旧 Redis。
        redisValue.set("old-shop-json-rebuilt-after-first-delete");
        assertThat(redisValue).hasValue("old-shop-json-rebuilt-after-first-delete");

        String canalEvent = """
                {"id":9001,"table":"tb_shop","type":"UPDATE","data":[{"id":"9"}]}
                """;
        binlogConsumer.onMessage(canalEvent);

        // 第二次删除：Canal 延迟事件必须删掉第一次删除后被旧加载重新写入的值。
        assertThat(redisValue).hasValue(null);
        verify(redis, times(2)).delete("cache:shop:9");
        ArgumentCaptor<String> notifications = ArgumentCaptor.forClass(String.class);
        verify(rocketMQTemplate).syncSend(
                eq(MqConstants.SHOP_LOCAL_INVALIDATION_TOPIC), notifications.capture());

        ShopLocalCache firstCache = cacheContainingOldShop(9L);
        ShopLocalCache secondCache = cacheContainingOldShop(9L);
        ShopLocalInvalidationConsumer first = localConsumer(firstCache, "app-1");
        ShopLocalInvalidationConsumer second = localConsumer(secondCache, "app-2");
        String successfullyPublished = notifications.getValue();

        first.onMessage(successfullyPublished);
        second.onMessage(successfullyPublished);

        assertThat(firstCache.getIfPresent(9L)).isNull();
        assertThat(secondCache.getIfPresent(9L)).isNull();
    }

    private ShopServiceImpl serviceWith(StringRedisTemplate redis,
                                        ShopMapper shopMapper,
                                        ShopLocalCache localCache) {
        ShopServiceImpl service = new ShopServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(service, "shopLocalCache", localCache);
        ReflectionTestUtils.setField(service, "baseMapper", shopMapper);
        return service;
    }

    private ShopLocalCache cacheContainingOldShop(long shopId) {
        ShopLocalCache cache = new ShopLocalCache();
        cache.getOrLoad(shopId, () -> new Shop().setId(shopId).setName("旧值"));
        return cache;
    }

    private ShopLocalInvalidationConsumer localConsumer(ShopLocalCache cache, String instanceId) {
        ShopLocalInvalidationConsumer consumer =
                new ShopLocalInvalidationConsumer(cache, objectMapper);
        ReflectionTestUtils.setField(consumer, "instanceId", instanceId);
        return consumer;
    }
}

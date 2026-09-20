package com.dianping.xpro;

import com.dianping.xpro.dto.Result;
import com.dianping.xpro.entity.Shop;
import com.dianping.xpro.mapper.ShopMapper;
import com.dianping.xpro.service.impl.ShopServiceImpl;
import com.dianping.xpro.utils.ShopLocalCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopServiceImplTest {

    @Test
    void cacheRebuildStartsOnlyAfterTheCurrentThreadAcquiresTheLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ShopMapper shopMapper = mock(ShopMapper.class);
        ShopServiceImpl service = serviceWith(redis, shopMapper);
        Shop shop = new Shop().setId(1L).setName("测试商铺");

        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("cache:shop:1")).thenReturn(null);
        when(valueOperations.setIfAbsent(
                eq("lock:shop:1"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(false, true);
        when(shopMapper.selectById(1L)).thenReturn(shop);

        Result result = service.queryById(1L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isSameAs(shop);
        InOrder rebuildOrder = inOrder(valueOperations, shopMapper);
        rebuildOrder.verify(valueOperations, times(2)).setIfAbsent(
                eq("lock:shop:1"), anyString(), eq(10L), eq(TimeUnit.SECONDS));
        rebuildOrder.verify(shopMapper).selectById(1L);
        verify(shopMapper).selectById(1L);
        verify(redis).execute(any(), anyList(), any());
        verify(redis, never()).delete("lock:shop:1");
    }

    @Test
    void shopCacheIsInvalidatedOnlyAfterTransactionCommit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ShopMapper shopMapper = mock(ShopMapper.class);
        ShopServiceImpl service = serviceWith(redis, shopMapper);
        Shop shop = new Shop().setId(7L).setName("新名称");
        when(shopMapper.updateById(shop)).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            Result result = service.updateShop(shop);

            assertThat(result.getSuccess()).isTrue();
            verify(redis, never()).delete("cache:shop:7");

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.getFirst().afterCommit();

            verify(redis).delete("cache:shop:7");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static ShopServiceImpl serviceWith(StringRedisTemplate redis, ShopMapper shopMapper) {
        ShopServiceImpl service = new ShopServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "shopLocalCache", new ShopLocalCache());
        ReflectionTestUtils.setField(service, "baseMapper", shopMapper);
        return service;
    }
}

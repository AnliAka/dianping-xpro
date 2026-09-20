package com.dianping.xpro.service.impl;

import com.dianping.xpro.dto.Result;
import com.dianping.xpro.entity.Shop;
import com.dianping.xpro.mapper.ShopMapper;
import com.dianping.xpro.service.IShopService;
import com.dianping.xpro.utils.RedisLock;
import com.dianping.xpro.utils.ShopLocalCache;

import cn.hutool.core.util.StrUtil;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import static com.dianping.xpro.utils.RedisConstants.CACHE_NULL_TTL;
import static com.dianping.xpro.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.dianping.xpro.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.dianping.xpro.utils.RedisConstants.LOCK_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    private static final String NULL_CACHE_VALUE = "null";
    private static final long LOCK_RETRY_DELAY_MILLIS = 50L;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShopLocalCache shopLocalCache;

    @Override
    public Result queryById(Long id){
        try {
            Shop shop = shopLocalCache.getOrLoad(id, () -> queryThroughRedis(id));
            return shop != null ? Result.ok(shop) : Result.fail("店铺不存在");
        } catch (CacheLoadInterruptedException e) {
            return Result.fail("查询被中断，请稍后重试");
        }
    }

    private Shop queryThroughRedis(Long id) {
        String cacheKey = CACHE_SHOP_KEY + id;
        RedisLock rebuildLock = new RedisLock("shop:" + id, stringRedisTemplate);

        while (true) {
            CacheLookup cached = queryShopFromCache(cacheKey);
            if (cached.hit()) {
                return cached.shop();
            }

            if (!rebuildLock.lock(LOCK_SHOP_TTL)) {
                waitBeforeRetry();
                continue;
            }

            try {
                // 获锁期间其他持有者可能已经完成回填，重建前必须再次检查。
                cached = queryShopFromCache(cacheKey);
                if (cached.hit()) {
                    return cached.shop();
                }

                Shop shop = baseMapper.selectById(id);
                writeShopToCache(cacheKey, shop);
                return shop;
            } finally {
                // RedisLock 会通过 Lua 校验持有者标识，当前线程只能释放自己的锁。
                rebuildLock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop){
        if(shop.getId() == null){
            return Result.fail("店铺id不能为空");
        }
        // 更新数据库
        updateById(shop);
        invalidateShopCacheAfterCommit(shop.getId());
        return Result.ok();
    }

    private CacheLookup queryShopFromCache(String cacheKey) {
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isBlank(json)) {
            return CacheLookup.miss();
        }
        if (NULL_CACHE_VALUE.equals(json)) {
            return CacheLookup.hit(null);
        }
        try {
            return CacheLookup.hit(objectMapper.readValue(json, Shop.class));
        } catch (Exception e) {
            log.warn("无法解析商铺缓存，将重新加载: key={}", cacheKey, e);
            return CacheLookup.miss();
        }
    }

    private void writeShopToCache(String cacheKey, Shop shop) {
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(
                    cacheKey, NULL_CACHE_VALUE, CACHE_NULL_TTL, TimeUnit.MINUTES);
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    cacheKey, objectMapper.writeValueAsString(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("无法写入商铺缓存: key={}", cacheKey, e);
        }
    }

    private void waitBeforeRetry() {
        try {
            Thread.sleep(LOCK_RETRY_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CacheLoadInterruptedException();
        }
    }

    private void invalidateShopCacheAfterCommit(Long shopId) {
        String cacheKey = CACHE_SHOP_KEY + shopId;
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            stringRedisTemplate.delete(cacheKey);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                stringRedisTemplate.delete(cacheKey);
            }
        });
    }

    private record CacheLookup(boolean hit, Shop shop) {
        private static CacheLookup miss() {
            return new CacheLookup(false, null);
        }

        private static CacheLookup hit(Shop shop) {
            return new CacheLookup(true, shop);
        }
    }

    private static final class CacheLoadInterruptedException extends RuntimeException {
    }
}

package com.dianping.xpro.utils;

import com.dianping.xpro.entity.Shop;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

import static com.dianping.xpro.utils.RedisConstants.LOCAL_SHOP_CACHE_MAXIMUM_SIZE;
import static com.dianping.xpro.utils.RedisConstants.LOCAL_SHOP_CACHE_TTL;

/**
 * 手动维护的单实例商铺缓存。不使用 {@code @Cacheable}，读取、回填和失效顺序均显式可见。
 *
 * <p>加载和失效按 shopId 映射到同一把条带锁：如果失效到达时旧加载尚未结束，失效会在
 * 旧加载回填之后执行，避免旧值在 {@link #invalidate(Long)} 返回后重新进入本地缓存。</p>
 */
@Component
public class ShopLocalCache {

    private static final int LOAD_GUARD_COUNT = 256;

    private final boolean enabled;
    private final Cache<Long, Shop> cache;
    private final Object[] loadGuards = new Object[LOAD_GUARD_COUNT];

    public ShopLocalCache() {
        this(true);
    }

    @Autowired
    public ShopLocalCache(@Value("${app.shop-cache.local-enabled:true}") boolean enabled) {
        this.enabled = enabled;
        this.cache = Caffeine.newBuilder()
                .maximumSize(LOCAL_SHOP_CACHE_MAXIMUM_SIZE)
                .expireAfterWrite(Duration.ofMinutes(LOCAL_SHOP_CACHE_TTL))
                .build();
        for (int i = 0; i < loadGuards.length; i++) {
            loadGuards[i] = new Object();
        }
    }

    public Shop getOrLoad(Long shopId, Supplier<Shop> loader) {
        if (!enabled) {
            return loader.get();
        }
        Shop cached = cache.getIfPresent(shopId);
        if (cached != null) {
            return cached;
        }

        synchronized (guardFor(shopId)) {
            cached = cache.getIfPresent(shopId);
            if (cached != null) {
                return cached;
            }
            Shop loaded = loader.get();
            if (loaded != null) {
                cache.put(shopId, loaded);
            }
            return loaded;
        }
    }

    public void invalidate(Long shopId) {
        if (!enabled) {
            return;
        }
        synchronized (guardFor(shopId)) {
            cache.invalidate(shopId);
        }
    }

    public Shop getIfPresent(Long shopId) {
        if (!enabled) {
            return null;
        }
        return cache.getIfPresent(shopId);
    }

    private Object guardFor(Long shopId) {
        return loadGuards[Math.floorMod(Long.hashCode(shopId), loadGuards.length)];
    }
}

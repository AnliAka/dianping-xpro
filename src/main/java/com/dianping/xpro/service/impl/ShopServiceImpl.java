package com.dianping.xpro.service.impl;

import com.dianping.xpro.dto.Result;
import com.dianping.xpro.entity.Shop;
import com.dianping.xpro.mapper.ShopMapper;
import com.dianping.xpro.service.IShopService;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;
    @Override
    public Result queryById(Long id){
        // 1. 从缓存中查询
        String key = "shop:" + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            if ("null".equals(json)) {
                return Result.fail("店铺不存在");
            }
            try {
                Shop shop = objectMapper.readValue(json, Shop.class);
                return Result.ok(shop);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        // 2. 缓存未命中，尝试获取互斥锁
        String lockKey = "lock:shop:" + id;
        boolean isLock = tryLock(lockKey);
        if (!isLock) {
            // 3. 未获得锁，休眠一段时间后重试查询缓存
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                try {
                    Shop shop = objectMapper.readValue(json, Shop.class);
                    return Result.ok(shop);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
            // 4. 获得锁，查询数据库
            Shop shop = baseMapper.selectById(id);
            if (shop != null) {
                try {
                    stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(shop), 30L, TimeUnit.MINUTES);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                stringRedisTemplate.opsForValue().set(key, "null", 30L, TimeUnit.MINUTES);
            }
            // 5. 释放互斥锁
            unlock(lockKey);
            return shop != null ? Result.ok(shop) : Result.fail("店铺不存在");
    }
    private boolean tryLock(String key) {
        // 因为setIfAbsent还会返回null对象
        return BooleanUtil.isTrue(stringRedisTemplate.opsForValue().setIfAbsent(key, "lock", 30L, TimeUnit.SECONDS));
    }
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
    @Override
    @Transactional
    public Result updateShop(Shop shop){
        if(shop.getId() == null){
            return Result.fail("店铺id不能为空");
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        String key = "shop:" + shop.getId();
        stringRedisTemplate.delete(key);
        return Result.ok();
    }
}

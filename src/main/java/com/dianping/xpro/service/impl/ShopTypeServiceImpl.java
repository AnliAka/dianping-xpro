package com.dianping.xpro.service.impl;

import com.dianping.xpro.dto.Result;
import com.dianping.xpro.entity.ShopType;
import com.dianping.xpro.mapper.ShopTypeMapper;
import com.dianping.xpro.service.IShopTypeService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key = "show:typeList";
        // 从缓存中获取数据
        String cacheJson = stringRedisTemplate.opsForValue().get(key);
        if (cacheJson != null) {
            // 从缓存中解析数据
            try {
                // 使用 TypeReference 来正确反序列化为 List<ShopType>
                List<ShopType> typeList = objectMapper.readValue(cacheJson, new TypeReference<List<ShopType>>() {});
                return Result.ok(typeList);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
            List<ShopType> typeList = query().orderByAsc("sort").list();
            // 缓存数据
            try {
                stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(typeList));
            } catch (Exception e) {
                e.printStackTrace();
            }
            return Result.ok(typeList);
        
    }
}

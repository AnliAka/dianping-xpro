package com.dianping.xpro.utils;

import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    public long nextId(String keyPrefix) {
        // 1. 生成时间戳
        long timestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        
        // 2. 生成序列号
        Long sequence = Optional.ofNullable(
        stringRedisTemplate.opsForValue().increment(keyPrefix)).orElse(0L);
        return timestamp << 32 | sequence;
    }
}

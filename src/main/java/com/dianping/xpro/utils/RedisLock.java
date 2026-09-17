package com.dianping.xpro.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import cn.hutool.core.lang.UUID;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class RedisLock implements ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    public RedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";  //这里uuid加个true可以去掉-，为了避免线程ID重复
    private static final DefaultRedisScript<Long> unlockScript = new DefaultRedisScript<>();
    static {
        unlockScript.setLocation(new ClassPathResource("unlock.lua"));
        unlockScript.setResultType(Long.class);
    }
    @Override
    public boolean lock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().threadId();
        Boolean lockResult = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(lockResult);
    }
    @Override
    public void unlock() {
        // 获取线程ID
        String threadId = ID_PREFIX + Thread.currentThread().threadId();
        // 调用脚本
        stringRedisTemplate.execute(unlockScript, Collections.singletonList(KEY_PREFIX + name), threadId);
    }
    // @Override
    // public void unlock() {
    //     // 获取线程ID
    //     String threadId = ID_PREFIX + Thread.currentThread().threadId();
    //     // 获取redis中的id
    //     String redisId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
    //     if (threadId.equals(redisId)) {
    //         stringRedisTemplate.delete(KEY_PREFIX + name);
    //     }
    // }
}

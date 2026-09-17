package com.dianping.xpro.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import com.dianping.xpro.dto.UserDTO;

import cn.hutool.core.bean.BeanUtil;


@Component
public class RefreshIntercepter implements HandlerInterceptor {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) throws Exception {
        String token = request.getHeader("authorization");
        if (token == null){
            return true;
        }
        // key 前缀与 TTL 与 UserServiceImpl 的写入逻辑保持一致，
        // 统一取自 RedisConstants，避免两处硬编码不同步导致登录态读不到。
        String loginKey = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> claims = stringRedisTemplate.opsForHash().entries(loginKey);
        if (claims.isEmpty()){
            return true;
        }
        UserDTO userDTO = BeanUtil.toBean(claims, UserDTO.class);
        UserHolder.saveUser(userDTO);
        // 刷新token的过期时间
        stringRedisTemplate.expire(loginKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }
}

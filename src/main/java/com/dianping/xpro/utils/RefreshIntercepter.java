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
        Map<Object, Object> claims = stringRedisTemplate.opsForHash().entries("login:user:" + token);
        if (claims.isEmpty()){
            return true;
        }
        UserDTO userDTO = BeanUtil.toBean(claims, UserDTO.class);
        UserHolder.saveUser(userDTO);
        // 刷新token的过期时间
        stringRedisTemplate.expire("login:user:" + token, 30, TimeUnit.MINUTES);
        return true;
    }
}

package com.dianping.xpro.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.dianping.xpro.dto.LoginFormDTO;
import com.dianping.xpro.dto.Result;
import com.dianping.xpro.dto.UserDTO;
import com.dianping.xpro.entity.User;
import com.dianping.xpro.mapper.UserMapper;
import com.dianping.xpro.service.IUserService;
import com.dianping.xpro.utils.RedisConstants;
import com.dianping.xpro.utils.RegexUtils;

import cn.hutool.core.util.RandomUtil;
import lombok.extern.slf4j.Slf4j;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    
    @Override
    public Result sendCode(String phone) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            // 2. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 3. 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4. 保存手机号和验证码到Redis
        stringRedisTemplate.opsForValue().set(
                RedisConstants.LOGIN_CODE_KEY + phone, code,
                RedisConstants.LOGIN_CODE_TTL, TimeUnit.SECONDS);
        // 5. 发送验证码
        log.info("验证码: {}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            // 2. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 3. 符合，校验验证码,从redis中获取
        String code = stringRedisTemplate.opsForValue()
                .get(RedisConstants.LOGIN_CODE_KEY + loginForm.getPhone());
        if (code == null || !code.equals(loginForm.getCode())){
            // 4. 校验失败，返回错误信息
            return Result.fail("验证码错误");
        }
        // 5. 校验成功，根据手机号查询出用户 select * from tb_user where phone = ?
        String phone = loginForm.getPhone();
        User user = query().eq("phone", phone).one();
        // 6. 判断用户是否存在
        if(user == null) {
            // 7. 不存在，创建用户并保存
            user = createUserWithPhone(phone);
        }
        // 7. 保存用户到redis
        // 7.1 生成token
        String token = RandomUtil.randomNumbers(32);
        // 7.2 将对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> claims = BeanUtil.beanToMap(
            userDTO,
            new HashMap<>(),
            CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        // 7.3 保存用户到redis
        // key 前缀与 TTL 统一取自 RedisConstants，与 RefreshIntercepter 的读取逻辑保持一致。
        String loginKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(loginKey, claims);
        stringRedisTemplate.expire(loginKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 8. 返回成功信息
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("momo");
        save(user);
        return user;
    }
}

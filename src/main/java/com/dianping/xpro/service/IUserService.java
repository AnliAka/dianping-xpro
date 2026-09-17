package com.dianping.xpro.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.dianping.xpro.dto.LoginFormDTO;
import com.dianping.xpro.dto.Result;
import com.dianping.xpro.entity.User;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone);

    Result login(LoginFormDTO loginForm);

}

package com.dianping.xpro.service.impl;

import com.dianping.xpro.entity.UserInfo;
import com.dianping.xpro.mapper.UserInfoMapper;
import com.dianping.xpro.service.IUserInfoService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-24
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}

package com.dianping.xpro.service.impl;

import com.dianping.xpro.entity.BlogComments;
import com.dianping.xpro.mapper.BlogCommentsMapper;
import com.dianping.xpro.service.IBlogCommentsService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
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
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}

package com.dianping.xpro.service.impl;

import com.dianping.xpro.entity.Blog;
import com.dianping.xpro.mapper.BlogMapper;
import com.dianping.xpro.service.IBlogService;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

}

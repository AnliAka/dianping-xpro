package com.dianping.xpro.service;

import com.dianping.xpro.dto.Result;
import com.dianping.xpro.entity.ShopType;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IShopTypeService extends IService<ShopType> {

    Result queryTypeList();

}

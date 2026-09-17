package com.dianping.xpro.service;

import com.dianping.xpro.dto.Result;
import com.dianping.xpro.entity.Voucher;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    void addSeckillVoucher(Voucher voucher);
}

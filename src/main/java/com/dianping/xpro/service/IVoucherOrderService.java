package com.dianping.xpro.service;

import com.dianping.xpro.dto.Result;
import com.dianping.xpro.entity.VoucherOrder;
import com.dianping.xpro.mq.SeckillOrderMessage;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    /** 由建单消费者调用，按 orderId 幂等落单。 */
    void createVoucherOrder(SeckillOrderMessage message);

}

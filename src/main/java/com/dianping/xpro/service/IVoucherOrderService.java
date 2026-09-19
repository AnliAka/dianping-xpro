package com.dianping.xpro.service;

import com.dianping.xpro.dto.Result;
import com.dianping.xpro.entity.VoucherOrder;
import com.dianping.xpro.mq.SeckillOrderMessage;
import com.baomidou.mybatisplus.spring.service.IService;

public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    /** 由建单消费者调用，按 orderId 幂等落单。 */
    void createVoucherOrder(SeckillOrderMessage message);

    /** 模拟支付：status 1→2，仅在截止时间前且仍未支付时成功（唯一胜者之一）。 */
    Result payOrder(Long orderId);

    /** 用户主动取消：复用关单服务（status 1→4），与支付、超时关单竞争同一迁移。 */
    Result cancelOrder(Long orderId);

    /** 查询本人订单，供前端支付面板展示状态与倒计时。 */
    Result queryOrder(Long orderId);
}

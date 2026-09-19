package com.dianping.xpro.controller;

import com.dianping.xpro.dto.Result;
import com.dianping.xpro.service.IVoucherOrderService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 秒杀订单：下单、模拟支付、取消与查询。
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Autowired
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /** 模拟支付：仅待支付且未过截止时间的订单成功。 */
    @PostMapping("{id}/pay")
    public Result payOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.payOrder(orderId);
    }

    /** 用户主动取消订单。 */
    @PostMapping("{id}/cancel")
    public Result cancelOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.cancelOrder(orderId);
    }

    /** 查询本人订单（状态 + 支付截止时间），供支付面板倒计时与状态刷新。 */
    @GetMapping("{id}")
    public Result queryOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.queryOrder(orderId);
    }
}

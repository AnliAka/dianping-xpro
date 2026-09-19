package com.dianping.xpro.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.dianping.xpro.entity.OrderOutbox;
import com.dianping.xpro.entity.VoucherOrder;
import com.dianping.xpro.mapper.OrderOutboxMapper;
import com.dianping.xpro.mapper.VoucherOrderMapper;
import com.dianping.xpro.mq.MqConstants;
import com.dianping.xpro.mq.StockReleaseMessage;
import com.dianping.xpro.service.IOrderCloseService;
import com.dianping.xpro.service.ISeckillVoucherService;
import com.dianping.xpro.utils.OrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 关单事务：条件迁移 1→4、归还数据库库存、写预占释放 Outbox，三者原子。
 *
 * <p>独立于 VoucherOrderServiceImpl 成 Bean，是因为用户取消、延迟提醒消费者、
 * 定时扫描三方都要经代理调用它；若作为同类内部方法，自调用会绕开事务代理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCloseServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IOrderCloseService {

    private final ISeckillVoucherService seckillVoucherService;
    private final OrderOutboxMapper orderOutboxMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean closeOrder(Long orderId, boolean requireExpired) {
        VoucherOrder order = getById(orderId);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            // 快路径：非待支付直接放弃（历史订单 pay_deadline 为 NULL 也在此拦截）。
            return false;
        }

        // 唯一胜者的裁决处：同一行锁上，支付(1→2)、取消/超时(1→4)只有一个能拿到 affected=1。
        // 时间条件用应用侧参数而非 SQL 的 NOW()，避免应用与数据库会话时区不一致产生偏差。
        LocalDateTime now = LocalDateTime.now();
        boolean moved = update()
                .set("status", OrderStatus.CANCELLED)
                .eq("id", orderId)
                .eq("status", OrderStatus.PENDING_PAYMENT)
                .le(requireExpired, "pay_deadline", now)
                .update();
        if (!moved) {
            // 已被支付抢先，或（requireExpired 时）尚未到期。
            return false;
        }

        // 归还数据库库存。因为 1→4 只会发生一次，stock+1 对每个订单至多执行一次，
        // 不会重复归还；不设库存上限（总量未存表，靠迁移唯一性保证不超还）。
        boolean returned = seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        if (!returned) {
            throw new IllegalStateException("数据库库存归还失败, voucherId=" + order.getVoucherId());
        }

        // 释放 Outbox 与上述写入同事务：事务提交后由 relay 发布，Redis 预占随之归还。
        try {
            String payload = objectMapper.writeValueAsString(new StockReleaseMessage(
                    orderId, order.getVoucherId(), order.getUserId()));
            orderOutboxMapper.insert(new OrderOutbox()
                    .setOrderId(orderId)
                    .setType(MqConstants.OUTBOX_TYPE_STOCK_RELEASE)
                    .setPayload(payload));
        } catch (Exception e) {
            throw new IllegalStateException("释放 Outbox 写入失败, orderId=" + orderId, e);
        }

        log.info("订单已关闭并写入释放 Outbox：orderId={}, voucherId={}, userId={}, 超时关单={}",
                orderId, order.getVoucherId(), order.getUserId(), requireExpired);
        return true;
    }
}

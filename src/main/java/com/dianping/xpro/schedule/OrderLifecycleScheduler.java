package com.dianping.xpro.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dianping.xpro.entity.OrderOutbox;
import com.dianping.xpro.entity.VoucherOrder;
import com.dianping.xpro.mapper.OrderOutboxMapper;
import com.dianping.xpro.mq.CloseRemindMessage;
import com.dianping.xpro.mq.DelayLevels;
import com.dianping.xpro.mq.FaultInjector;
import com.dianping.xpro.mq.MqConstants;
import com.dianping.xpro.service.IOrderCloseService;
import com.dianping.xpro.service.IVoucherOrderService;
import com.dianping.xpro.utils.OrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单生命周期补偿：Outbox relay 与到期未支付订单的兜底扫描。
 *
 * <p>两条职责共用"幂等 + 可重试"两个前提：
 * <ul>
 *   <li>relay：事务落库但尚未发布的 Outbox 记录反复重试发布；发布成功才标记，
 *       标记失败造成的重复消息由消费端幂等吸收。</li>
 *   <li>扫描：延迟提醒丢失（进程宕机、MQ 故障、故障注入）时，兜底关单走与
 *       延迟提醒完全相同的 closeOrder，无新增状态路径。</li>
 * </ul></p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.order.lifecycle-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OrderLifecycleScheduler {

    /** relay 周期（毫秒）。 */
    private static final long RELAY_INTERVAL_MILLIS = 5000;

    /** 兜底扫描周期（毫秒）。 */
    private static final long SCAN_INTERVAL_MILLIS = 10_000;

    /**
     * 宽限期（秒）：到期后先等延迟提醒路径处理，超过宽限期仍未关闭的订单
     * 才由扫描兜底——正常路径永远轮不到扫描出手，扫描只兜底不抢活。
     */
    private static final long SCAN_GRACE_SECONDS = 60;

    /** 单轮最多处理条数，防止积压时一次拉爆。 */
    private static final int BATCH_LIMIT = 200;

    private final OrderOutboxMapper orderOutboxMapper;
    private final IVoucherOrderService voucherOrderService;
    private final IOrderCloseService orderCloseService;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final FaultInjector faultInjector;

    /**
     * Outbox relay：发布待发布记录。
     */
    @Scheduled(fixedDelay = RELAY_INTERVAL_MILLIS)
    public void relayOutbox() {
        List<OrderOutbox> pending = orderOutboxMapper.selectList(
                new LambdaQueryWrapper<OrderOutbox>()
                        .eq(OrderOutbox::getStatus, 0)
                        .last("LIMIT " + BATCH_LIMIT));
        for (OrderOutbox outbox : pending) {
            try {
                publish(outbox);
                // 先发布后标记：标记失败会重复发布，消费端幂等吸收重复。
                orderOutboxMapper.updateById(new OrderOutbox()
                        .setId(outbox.getId())
                        .setStatus(1)
                        .setPublishTime(LocalDateTime.now()));
            } catch (Exception e) {
                // 记录保持待发布，下一轮重试。单条失败不阻断同批其他记录。
                log.error("Outbox 发布失败，待下轮重试：id={}, type={}", outbox.getId(), outbox.getType(), e);
            }
        }
    }

    private void publish(OrderOutbox outbox) throws Exception {
        switch (outbox.getType()) {
            case MqConstants.OUTBOX_TYPE_CLOSE_REMIND -> publishCloseRemind(outbox);
            case MqConstants.OUTBOX_TYPE_STOCK_RELEASE -> publishStockRelease(outbox);
            default -> log.warn("未知 Outbox 类型，跳过：id={}, type={}", outbox.getId(), outbox.getType());
        }
    }

    private void publishCloseRemind(OrderOutbox outbox) throws Exception {
        CloseRemindMessage remind = objectMapper.readValue(outbox.getPayload(), CloseRemindMessage.class);
        if (faultInjector.dropCloseRemind(remind.voucherId())) {
            // 故障注入：不发消息但照常标记已发布，模拟"提醒消息在途中丢失"，
            // 到期后由兜底扫描关单——用于验证对账核验遗漏提醒后的库存回收。
            log.warn("【故障注入】丢弃关单提醒，等待定时扫描兜底：orderId={}", remind.orderId());
            return;
        }
        long remainingMillis = remind.payDeadlineMillis() - System.currentTimeMillis();
        int level = remainingMillis <= 0 ? 1 : DelayLevels.forRemainingMillis(remainingMillis);
        rocketMQTemplate.syncSend(
                MqConstants.ORDER_CLOSE_REMIND_TOPIC,
                MessageBuilder.withPayload(outbox.getPayload()).build(),
                3000, level);
    }

    private void publishStockRelease(OrderOutbox outbox) {
        rocketMQTemplate.syncSend(
                MqConstants.ORDER_STOCK_RELEASE_TOPIC,
                MessageBuilder.withPayload(outbox.getPayload()).build());
    }

    /**
     * 兜底扫描：到期超过宽限期仍未支付、仍未关闭的订单，复用关单服务关闭。
     */
    @Scheduled(fixedDelay = SCAN_INTERVAL_MILLIS)
    public void closeExpiredUnpaidOrders() {
        List<VoucherOrder> expired = voucherOrderService.query()
                .eq("status", OrderStatus.PENDING_PAYMENT)
                .lt("pay_deadline", LocalDateTime.now().minusSeconds(SCAN_GRACE_SECONDS))
                .last("LIMIT " + BATCH_LIMIT)
                .list();
        if (expired.isEmpty()) {
            return;
        }
        log.warn("兜底扫描发现 {} 笔到期未关闭订单（延迟提醒丢失或消费失败）", expired.size());
        for (VoucherOrder order : expired) {
            try {
                boolean closed = orderCloseService.closeOrder(order.getId(), true);
                log.info("兜底关单结果：orderId={}, closed={}", order.getId(), closed);
            } catch (Exception e) {
                log.error("兜底关单失败，等待下轮重试：orderId={}", order.getId(), e);
            }
        }
    }
}

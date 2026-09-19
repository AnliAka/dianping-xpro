package com.dianping.xpro.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.dianping.xpro.dto.Result;
import com.dianping.xpro.entity.OrderOutbox;
import com.dianping.xpro.entity.VoucherOrder;
import com.dianping.xpro.mapper.OrderOutboxMapper;
import com.dianping.xpro.mapper.VoucherOrderMapper;
import com.dianping.xpro.mq.CloseRemindMessage;
import com.dianping.xpro.mq.MqConstants;
import com.dianping.xpro.mq.SeckillOrderMessage;
import com.dianping.xpro.ratelimit.SeckillRateLimiter;
import com.dianping.xpro.service.IOrderCloseService;
import com.dianping.xpro.service.ISeckillVoucherService;
import com.dianping.xpro.service.IVoucherOrderService;
import com.dianping.xpro.utils.OrderStatus;
import com.dianping.xpro.utils.RedisConstants;
import com.dianping.xpro.utils.RedisIdWorker;
import com.dianping.xpro.utils.UserHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 秒杀下单：入口以 RocketMQ 事务消息驱动，库存竞争发生在 Redis 预占阶段。
 *
 * <p>入口链路：用户／活动滑动窗口频控 → 生成本次请求唯一的 orderId → 发出携带
 * orderId/userId/voucherId 的半消息 → 事务监听器执行 {@code seckill.lua} 预占 →
 * 按预占结果 COMMIT 或 ROLLBACK。
 * 半消息在提交前对消费者不可见，因此"预占成功但消息未发出"的窗口由 Broker 回查兜住。</p>
 *
 * <p>订单生命周期（待支付 → 已支付/已取消）的三个竞争方——支付、用户取消、
 * 超时关单——共用"条件 UPDATE + 行锁"裁决唯一胜者，互斥不需要额外协调。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final ISeckillVoucherService seckillVoucherService;
    private final IOrderCloseService orderCloseService;
    private final OrderOutboxMapper orderOutboxMapper;
    private final RedisIdWorker redisIdWorker;
    private final StringRedisTemplate stringRedisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final SeckillRateLimiter seckillRateLimiter;

    /** 支付截止时长（分钟），见 application.yml 的 app.order.pay-deadline-minutes。 */
    @Value("${app.order.pay-deadline-minutes:10}")
    private long payDeadlineMinutes;

    /**
     * 秒杀入口。双层限流的第二层（用户／活动滑动窗口）在发送半消息之前执行：
     * 被拒请求不触发预占，无库存副作用。第一层（OpenResty 令牌桶）在网关完成。
     * orderId 在发送半消息之前生成，作为业务事务 id 贯穿半消息、预占结果与订单主键。
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        if (!seckillRateLimiter.tryAcquire(voucherId, userId)) {
            log.info("SECKILL_METRICS outcome=USER_REJECTED, voucherId={}, userId={}", voucherId, userId);
            return Result.fail("操作过于频繁，请稍后再试");
        }

        long orderId = redisIdWorker.nextId("order");
        try {
            String body = objectMapper.writeValueAsString(
                    new SeckillOrderMessage(orderId, userId, voucherId));
            TransactionSendResult sendResult = rocketMQTemplate.sendMessageInTransaction(
                    MqConstants.SECKILL_ORDER_TOPIC,
                    MessageBuilder.withPayload(body).build(),
                    null);

            LocalTransactionState state = sendResult.getLocalTransactionState();
            if (state == LocalTransactionState.COMMIT_MESSAGE) {
                log.info("SECKILL_METRICS outcome=ACCEPTED, orderId={}, voucherId={}, userId={}",
                        orderId, voucherId, userId);
                // 雪花订单号超过 JavaScript Number 的安全整数范围，必须按字符串返回；
                // 否则浏览器会把 639250219233445879 舍入成 639250219233445900，
                // 后续查询和支付都会拿错误的订单号。
                return Result.ok(Long.toString(orderId));
            }
            if (state == LocalTransactionState.ROLLBACK_MESSAGE) {
                String reason = describeReject(orderId, voucherId, userId);
                log.info("SECKILL_METRICS outcome=BUSINESS_REJECTED, orderId={}, voucherId={}, userId={}, reason={}",
                        orderId, voucherId, userId, reason);
                return Result.fail(reason);
            }
            // 本地事务结果未知：不得谎报成功，也不宜直接判失败。
            log.info("SECKILL_METRICS outcome=UNKNOWN, orderId={}, voucherId={}, userId={}",
                    orderId, voucherId, userId);
            return Result.fail("下单请求处理中，请稍后查询订单");
        } catch (Exception e) {
            log.error("SECKILL_METRICS outcome=SYSTEM_EXCEPTION, orderId={}, voucherId={}, userId={}",
                    orderId, voucherId, userId, e);
            return Result.fail("下单失败，请稍后重试");
        }
    }

    /**
     * 事务监听器只回报 ROLLBACK，具体原因需回读预占结果与当前库存判断。
     * 并发下原因可能不精确，仅用于提示文案。
     */
    private String describeReject(Long orderId, Long voucherId, Long userId) {
        String state = stringRedisTemplate.opsForValue()
                .get(RedisConstants.SECKILL_TX_KEY + orderId);
        if (!RedisConstants.SECKILL_TX_REJECTED.equals(state)) {
            return "秒杀失败";
        }
        String stock = stringRedisTemplate.opsForValue()
                .get(RedisConstants.SECKILL_STOCK_KEY + voucherId);
        if (stock == null || Long.parseLong(stock) <= 0) {
            return "秒杀券已售罄";
        }
        if (Boolean.TRUE.equals(stringRedisTemplate.opsForHash()
                .hasKey(RedisConstants.SECKILL_ORDER_KEY + voucherId, userId.toString()))) {
            return "用户已购买该秒杀券";
        }
        return "秒杀失败";
    }

    /**
     * 幂等落单。由建单消费者调用，重复投递不得重复扣减库存。
     * 与订单落库同一事务写入关单提醒 Outbox，提交后由 relay 以延迟消息发布。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(SeckillOrderMessage message) {
        LocalDateTime payDeadline = LocalDateTime.now().plusMinutes(payDeadlineMinutes);

        // 1. 先插订单：订单主键与 (user_id, active_voucher_id) 条件唯一约束共同充当幂等闸门。
        //    已取消订单的 active_voucher_id 为 NULL，允许重新购买；其他状态仍保持一人一单。
        VoucherOrder order = new VoucherOrder()
                .setId(message.orderId())
                .setUserId(message.userId())
                .setVoucherId(message.voucherId())
                .setPayType(1)
                .setStatus(OrderStatus.PENDING_PAYMENT)
                .setPayDeadline(payDeadline)
                .setCreateTime(LocalDateTime.now());
        try {
            save(order);
        } catch (DuplicateKeyException e) {
            if (getById(message.orderId()) != null) {
                // 同一订单的重复投递，按幂等成功处理。
                return;
            }
            // 同用户同券但订单号不同：说明入口预占出现异常，不能静默放过。
            throw e;
        }

        // 2. 条件扣减数据库库存。失败必须抛出：静默返回会让"已建单但未扣库存"被当成消费成功。
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", message.voucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            throw new IllegalStateException("数据库库存扣减失败, voucherId=" + message.voucherId());
        }

        // 3. 关单提醒 Outbox：与订单、库存同事务。事务提交后 relay 发布延迟消息，
        //    到期由消费端按 pay_deadline 执行关单；消息丢失由定时扫描兜底。
        insertCloseRemindOutbox(message, payDeadline);
    }

    private void insertCloseRemindOutbox(SeckillOrderMessage message, LocalDateTime payDeadline) {
        try {
            String payload = objectMapper.writeValueAsString(new CloseRemindMessage(
                    message.orderId(),
                    message.voucherId(),
                    payDeadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
            orderOutboxMapper.insert(new OrderOutbox()
                    .setOrderId(message.orderId())
                    .setType(MqConstants.OUTBOX_TYPE_CLOSE_REMIND)
                    .setPayload(payload));
        } catch (Exception e) {
            throw new IllegalStateException("关单提醒 Outbox 写入失败, orderId=" + message.orderId(), e);
        }
    }

    /**
     * 模拟支付（余额支付，直接成功）。与用户取消、超时关单竞争同一行：
     * 条件 UPDATE 里带上"截止时间前 + 仍未支付"，拿不到 affected=1 即为输家。
     */
    @Override
    public Result payOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            return Result.fail("订单不存在");
        }
        boolean moved = update()
                .set("status", OrderStatus.PAID)
                .set("pay_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("user_id", userId)
                .eq("status", OrderStatus.PENDING_PAYMENT)
                .gt("pay_deadline", LocalDateTime.now())
                .update();
        if (moved) {
            return Result.ok();
        }
        return Result.fail(describeMigrationFailure(orderId));
    }

    /**
     * 用户主动取消。复用关单服务（关单事务归还库存并写释放 Outbox），
     * 关单与支付互斥由 closeOrder 内部的条件 UPDATE 保证。
     */
    @Override
    public Result cancelOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            return Result.fail("订单不存在");
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return Result.fail("当前订单状态不可取消");
        }
        boolean closed = orderCloseService.closeOrder(orderId, false);
        if (!closed) {
            // 竞态：支付或超时关单刚先一步完成迁移。
            return Result.fail("订单状态已变化，取消失败");
        }
        return Result.ok();
    }

    @Override
    public Result queryOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            return Result.fail("订单不存在");
        }
        return Result.ok(order);
    }

    /**
     * 迁移失败时回读当前状态，给出可区分的提示文案。
     */
    private String describeMigrationFailure(Long orderId) {
        VoucherOrder current = getById(orderId);
        if (current == null) {
            return "订单不存在";
        }
        if (current.getStatus() == OrderStatus.PAID) {
            return "订单已支付，请勿重复支付";
        }
        if (current.getStatus() == OrderStatus.CANCELLED) {
            return "订单已关闭";
        }
        return "已超过支付截止时间，订单将被关闭";
    }
}

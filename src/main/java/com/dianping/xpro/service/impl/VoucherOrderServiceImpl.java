package com.dianping.xpro.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.dianping.xpro.dto.Result;
import com.dianping.xpro.entity.VoucherOrder;
import com.dianping.xpro.mapper.VoucherOrderMapper;
import com.dianping.xpro.mq.MqConstants;
import com.dianping.xpro.mq.SeckillOrderMessage;
import com.dianping.xpro.service.ISeckillVoucherService;
import com.dianping.xpro.service.IVoucherOrderService;
import com.dianping.xpro.utils.RedisConstants;
import com.dianping.xpro.utils.RedisIdWorker;
import com.dianping.xpro.utils.UserHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 秒杀下单：入口以 RocketMQ 事务消息驱动，库存竞争发生在 Redis 预占阶段。
 *
 * <p>入口链路：生成本次请求唯一的 orderId → 发出携带 orderId/userId/voucherId 的半消息 →
 * 事务监听器执行 {@code seckill.lua} 预占 → 按预占结果 COMMIT 或 ROLLBACK。
 * 半消息在提交前对消费者不可见，因此"预占成功但消息未发出"的窗口由 Broker 回查兜住。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final ISeckillVoucherService seckillVoucherService;
    private final RedisIdWorker redisIdWorker;
    private final StringRedisTemplate stringRedisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 秒杀入口。orderId 在发送半消息之前生成，作为业务事务 id 贯穿半消息、预占结果与订单主键。
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
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
                return Result.ok(orderId);
            }
            if (state == LocalTransactionState.ROLLBACK_MESSAGE) {
                return Result.fail(describeReject(orderId, voucherId, userId));
            }
            // 本地事务结果未知：不得谎报成功，也不宜直接判失败。
            return Result.fail("下单请求处理中，请稍后查询订单");
        } catch (Exception e) {
            log.error("秒杀半消息发送失败, voucherId={}, userId={}", voucherId, userId, e);
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
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(SeckillOrderMessage message) {
        // 1. 先插订单：订单主键与 (user_id, voucher_id) 唯一约束共同充当幂等闸门，
        //    让重复投递在最早一步失败，不做无用的库存扣减。
        VoucherOrder order = new VoucherOrder()
                .setId(message.orderId())
                .setUserId(message.userId())
                .setVoucherId(message.voucherId())
                .setPayType(1)
                .setStatus(1)
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
    }
}

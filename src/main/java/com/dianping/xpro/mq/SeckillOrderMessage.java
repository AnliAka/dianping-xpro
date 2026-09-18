package com.dianping.xpro.mq;

/**
 * 秒杀建单消息体。
 * orderId 同时充当 RocketMQ 的业务事务 id：半消息、预占事务结果
 * （seckill:tx:{orderId}）与数据库订单主键三处共用同一个值，回查时仅凭它即可定位预占结果。
 */
public record SeckillOrderMessage(Long orderId, Long userId, Long voucherId) {
}

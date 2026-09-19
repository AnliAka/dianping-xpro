package com.dianping.xpro.mq;

/**
 * 预占释放消息体。由关单事务写入 Outbox，消费端执行 release.lua 归还 Redis 预占。
 */
public record StockReleaseMessage(Long orderId, Long voucherId, Long userId) {
}

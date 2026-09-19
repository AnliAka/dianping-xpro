package com.dianping.xpro.mq;

/**
 * 关单提醒消息体。由建单事务写入 Outbox，relay 以延迟消息发布。
 *
 * <p>payDeadlineMillis 是绝对的截止时刻（epoch 毫秒）。固定延迟级别可能让消息提前到达，
 * 消费端会按剩余时间重投；是否真的关单始终以绝对截止时间的二次校验为准。</p>
 */
public record CloseRemindMessage(Long orderId, Long voucherId, long payDeadlineMillis) {
}

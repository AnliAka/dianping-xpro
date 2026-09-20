package com.dianping.xpro.mq;

/**
 * 应用实例本地缓存的失效通知。只传递失效意图，不携带也不回写 binlog 中的新旧行值。
 */
public record ShopLocalInvalidationMessage(Long shopId, String sourceEventId) {
}

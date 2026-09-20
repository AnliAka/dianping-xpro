package com.dianping.xpro.mq;

/**
 * 消息主题、消费者组与 Outbox 消息类型。
 */
public final class MqConstants {

    /** 秒杀建单消息主题。半消息在此主题上发出，入口预占作为其本地事务。 */
    public static final String SECKILL_ORDER_TOPIC = "seckill-order-topic";

    /** 建单消费者组。 */
    public static final String SECKILL_ORDER_CONSUMER_GROUP = "seckill-order-consumer-group";

    /** 关单提醒主题。消息只是"提醒"，是否执行关单以订单 pay_deadline 为准。 */
    public static final String ORDER_CLOSE_REMIND_TOPIC = "order-close-remind-topic";

    /** 关单提醒消费者组。 */
    public static final String ORDER_CLOSE_REMIND_CONSUMER_GROUP = "order-close-remind-consumer-group";

    /** 预占释放主题。关单事务提交后发布，消费端执行 release.lua 归还 Redis 预占。 */
    public static final String ORDER_STOCK_RELEASE_TOPIC = "order-stock-release-topic";

    /** 预占释放消费者组。 */
    public static final String ORDER_STOCK_RELEASE_CONSUMER_GROUP = "order-stock-release-consumer-group";

    /** Canal 将 tb_shop 的 ROW binlog 以 flat JSON 投递到该主题。 */
    public static final String SHOP_BINLOG_TOPIC = "shop-binlog-topic";

    /** 商铺 binlog 集群消费组：两个实例中只需一个负责有序删除共享 Redis。 */
    public static final String SHOP_REDIS_INVALIDATION_CONSUMER_GROUP =
            "shop-redis-invalidation-consumer-group";

    /** Redis 删除成功后发布的本地缓存失效主题，消息只包含 shopId 与源事件标识。 */
    public static final String SHOP_LOCAL_INVALIDATION_TOPIC = "shop-local-invalidation-topic";

    /** Outbox 类型：关单提醒（建单事务写入）。 */
    public static final String OUTBOX_TYPE_CLOSE_REMIND = "CLOSE_REMIND";

    /** Outbox 类型：预占释放（关单事务写入）。 */
    public static final String OUTBOX_TYPE_STOCK_RELEASE = "STOCK_RELEASE";

    private MqConstants() {
    }
}

package com.dianping.xpro.mq;

/**
 * 消息主题与消费者组。
 */
public final class MqConstants {

    /** 秒杀建单消息主题。半消息在此主题上发出，入口预占作为其本地事务。 */
    public static final String SECKILL_ORDER_TOPIC = "seckill-order-topic";

    /** 建单消费者组。 */
    public static final String SECKILL_ORDER_CONSUMER_GROUP = "seckill-order-consumer-group";

    private MqConstants() {
    }
}

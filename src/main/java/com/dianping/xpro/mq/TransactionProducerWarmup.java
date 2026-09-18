package com.dianping.xpro.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.impl.factory.MQClientInstance;
import org.apache.rocketmq.client.impl.producer.DefaultMQProducerImpl;
import org.apache.rocketmq.client.impl.producer.TopicPublishInfo;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 在应用启动阶段主动注册事务生产者。
 *
 * <p>RocketMQ 的生产者虽然会随 {@link RocketMQTemplate} 启动，但在首次发送前可能尚未加载
 * 业务 Topic 路由，因此不知道应向哪些 Broker 发送心跳。若应用恰好在 Redis 预占成功、
 * 返回 COMMIT 前宕机，重启后又没有新流量，Broker 会因找不到生产者信道而无法发起事务回查。</p>
 *
 * <p>本组件在 Spring 上下文就绪前加载秒杀 Topic 路由，并使用同一个事务生产者向相关 Broker
 * 发送心跳。预热失败时终止启动，避免一个无法回答事务回查的实例继续提供服务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.mq.producer-warmup.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class TransactionProducerWarmup implements SmartLifecycle {

    private static final int MAX_ATTEMPTS = 30;
    private static final long RETRY_INTERVAL_SECONDS = 1L;

    private final RocketMQTemplate rocketMQTemplate;
    private volatile boolean running;

    @Override
    public void start() {
        DefaultMQProducer producer = rocketMQTemplate.getProducer();
        if (!(producer instanceof TransactionMQProducer transactionProducer)
                || transactionProducer.getTransactionListener() == null) {
            throw new IllegalStateException("RocketMQ 事务生产者或事务监听器尚未初始化");
        }

        DefaultMQProducerImpl producerImpl = producer.getDefaultMQProducerImpl();
        MQClientInstance client = producerImpl.getMqClientFactory();
        if (client == null) {
            throw new IllegalStateException("RocketMQ 客户端实例尚未初始化");
        }

        String topic = producer.withNamespace(MqConstants.SECKILL_ORDER_TOPIC);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                client.updateTopicRouteInfoFromNameServer(topic);
                TopicPublishInfo publishInfo = producerImpl.getTopicPublishInfoTable().get(topic);
                boolean routeReady = publishInfo != null && publishInfo.ok();
                boolean heartbeatSent = routeReady && client.sendHeartbeatToAllBrokerWithLock();
                if (routeReady && heartbeatSent) {
                    running = true;
                    log.info("RocketMQ 事务生产者预热完成, group={}, topic={}, attempt={}",
                            producer.getProducerGroup(), topic, attempt);
                    return;
                }
                log.warn("RocketMQ 事务生产者预热未完成, group={}, topic={}, attempt={}, routeReady={}, heartbeatSent={}",
                        producer.getProducerGroup(), topic, attempt, routeReady, heartbeatSent);
            } catch (Exception e) {
                log.warn("RocketMQ 事务生产者预热失败, group={}, topic={}, attempt={}",
                        producer.getProducerGroup(), topic, attempt, e);
            }
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(RETRY_INTERVAL_SECONDS));
        }
        throw new IllegalStateException("RocketMQ 事务生产者在启动阶段未能注册 Broker");
    }

    @Override
    public void stop() {
        // Producer 的生命周期由 RocketMQTemplate 管理，这里只维护本组件的就绪状态。
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

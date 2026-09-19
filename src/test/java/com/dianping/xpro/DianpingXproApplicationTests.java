package com.dianping.xpro;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dianping.xpro.entity.Shop;
import com.dianping.xpro.entity.VoucherOrder;
import com.dianping.xpro.mapper.OrderOutboxMapper;
import com.dianping.xpro.mapper.ShopMapper;
import com.dianping.xpro.mq.DelayLevels;
import com.dianping.xpro.mq.FaultInjector;
import com.dianping.xpro.mq.OrderTransactionListener;
import com.dianping.xpro.mq.SeckillOrderMessage;
import com.dianping.xpro.service.IOrderCloseService;
import com.dianping.xpro.service.ISeckillVoucherService;
import com.dianping.xpro.service.impl.VoucherOrderServiceImpl;
import com.dianping.xpro.utils.OrderStatus;
import com.dianping.xpro.utils.RedisIdWorker;
import com.dianping.xpro.utils.UserHolder;
import com.dianping.xpro.dto.UserDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DianpingXproApplicationTests {

    @Test
    void boot3SupportsPaginationMvcRedisConfigurationAndIdempotentOrderCreation() {
        // Redisson 的网络连接与 RocketMQ 自动配置都被替换掉：
        // 这里只验证 Spring Boot 3 的上下文、分页、MVC 与本服务的落单事务语义。
        try (MockedStatic<Redisson> redisson = mockStatic(Redisson.class)) {
            redisson.when(() -> Redisson.create(any(Config.class))).thenReturn(mock(RedissonClient.class));
            new WebApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(DianpingXproApplication.class)
                    .withPropertyValues(
                            "spring.config.import=",
                            // 排除 RocketMQ 自动配置，避免测试期间真的去连 namesrv。
                            "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration",
                            "app.mq.producer-warmup.enabled=false",
                            "app.order.lifecycle-enabled=false",
                            "spring.datasource.url=jdbc:h2:mem:upgrade;MODE=MySQL",
                            "spring.datasource.driver-class-name=org.h2.Driver",
                            "spring.datasource.username=sa",
                            "spring.datasource.password=",
                            "spring.main.allow-circular-references=false",
                            "spring.data.redis.host=127.0.0.1",
                            "spring.data.redis.port=6380",
                            "spring.data.redis.database=2",
                            "spring.data.redis.ssl.enabled=true",
                            "spring.data.redis.username=test-user",
                            "spring.data.redis.password=test-password")
                    .withBean(RocketMQTemplate.class, () -> mock(RocketMQTemplate.class))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        ArgumentCaptor<Config> config = ArgumentCaptor.forClass(Config.class);
                        redisson.verify(() -> Redisson.create(config.capture()));
                        var redisServer = config.getValue().useSingleServer();
                        assertThat(redisServer.getAddress()).isEqualTo("rediss://127.0.0.1:6380");
                        assertThat(redisServer.getUsername()).isEqualTo("test-user");
                        assertThat(redisServer.getPassword()).isEqualTo("test-password");
                        assertThat(redisServer.getDatabase()).isEqualTo(2);

                        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                        jdbc.execute("""
                                CREATE TABLE tb_shop (
                                  id BIGINT PRIMARY KEY, name VARCHAR(100), type_id BIGINT,
                                  images VARCHAR(200), area VARCHAR(100), address VARCHAR(200),
                                  x DOUBLE, y DOUBLE, avg_price BIGINT, sold INT, comments INT,
                                  score INT, open_hours VARCHAR(100), create_time TIMESTAMP, update_time TIMESTAMP
                                )
                                """);
                        for (int id = 1; id <= 7; id++) {
                            jdbc.update("INSERT INTO tb_shop (id, name, type_id, create_time) VALUES (?, ?, 1, ?)",
                                    id, "Shop " + id, java.time.LocalDateTime.of(2026, 1, 1, 12, 0));
                        }
                        Page<Shop> page = context.getBean(ShopMapper.class).selectPage(new Page<>(2, 5),
                                new QueryWrapper<Shop>().orderByAsc("id"));
                        assertThat(page.getTotal()).isEqualTo(7);
                        assertThat(page.getRecords()).extracting(Shop::getId).containsExactly(6L, 7L);

                        var mvc = MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext()).build();
                        mvc.perform(get("/shop/of/type").param("typeId", "1").param("current", "2"))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                                .andExpect(jsonPath("$.data.length()").value(2))
                                .andExpect(jsonPath("$.data[0].createTime").value("2026-01-01T12:00:00"));
                        mvc.perform(post("/user/code").param("phone", "invalid"))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false));

                        jdbc.execute("CREATE TABLE tb_seckill_voucher (voucher_id BIGINT PRIMARY KEY, stock INT)");
                        jdbc.execute("""
                                CREATE TABLE tb_voucher_order (
                                  id BIGINT PRIMARY KEY, user_id BIGINT, voucher_id BIGINT,
                                  pay_type INT, status INT,
                                  active_voucher_id BIGINT GENERATED ALWAYS AS
                                    (CASE WHEN status = 4 THEN NULL ELSE voucher_id END),
                                  pay_deadline TIMESTAMP,
                                  create_time TIMESTAMP, pay_time TIMESTAMP,
                                  use_time TIMESTAMP, refund_time TIMESTAMP, update_time TIMESTAMP,
                                  UNIQUE (user_id, active_voucher_id)
                                )
                                """);
                        jdbc.execute("""
                                CREATE TABLE tb_order_outbox (
                                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                                  order_id BIGINT NOT NULL, type VARCHAR(32) NOT NULL,
                                  payload VARCHAR(2000) NOT NULL, status INT DEFAULT 0 NOT NULL,
                                  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                  publish_time TIMESTAMP
                                )
                                """);
                        jdbc.update("INSERT INTO tb_seckill_voucher VALUES (1, 2)");
                        VoucherOrderServiceImpl service = context.getBean(VoucherOrderServiceImpl.class);

                        // 首次落单：建单 + 扣库存
                        service.createVoucherOrder(new SeckillOrderMessage(1L, 1L, 1L));
                        assertThat(stockOf(jdbc)).isEqualTo(1);

                        // 重复投递：按幂等成功处理，不抛异常、不再扣库存、不产生第二张订单
                        service.createVoucherOrder(new SeckillOrderMessage(1L, 1L, 1L));
                        assertThat(stockOf(jdbc)).isEqualTo(1);
                        assertThat(orderCountOf(jdbc)).isEqualTo(1);

                        // 数据库库存耗尽：必须抛出触发重试，不得静默确认，且事务整体回滚
                        jdbc.update("UPDATE tb_seckill_voucher SET stock = 0 WHERE voucher_id = 1");
                        assertThatThrownBy(() -> service.createVoucherOrder(new SeckillOrderMessage(2L, 2L, 1L)))
                                .isInstanceOf(IllegalStateException.class);
                        assertThat(stockOf(jdbc)).isZero();
                        assertThat(orderCountOf(jdbc))
                                .as("库存扣减失败必须回滚已插入的订单").isEqualTo(1);

                        verifyOrderLifecycle(context.getBean(IOrderCloseService.class), service, jdbc);
                    });
        }
    }

    @Test
    void delayLevelsUseTheLargestLevelNotPastTheDeadline() {
        assertThat(DelayLevels.forRemainingMillis(500)).isEqualTo(1);
        assertThat(DelayLevels.forRemainingMillis(1_000)).isEqualTo(1);
        assertThat(DelayLevels.forRemainingMillis(4_999)).isEqualTo(1);
        assertThat(DelayLevels.forRemainingMillis(5_000)).isEqualTo(2);
        assertThat(DelayLevels.forRemainingMillis(TimeUnit.MINUTES.toMillis(11))).isEqualTo(14);
        assertThat(DelayLevels.forRemainingMillis(TimeUnit.HOURS.toMillis(3))).isEqualTo(18);
    }

    @Test
    void seckillReturnsLargeOrderIdAsStringWithoutJavaScriptPrecisionLoss() throws Exception {
        long orderId = 639250219233445879L;
        RedisIdWorker idWorker = mock(RedisIdWorker.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        TransactionSendResult sendResult = mock(TransactionSendResult.class);
        when(idWorker.nextId("order")).thenReturn(orderId);
        when(sendResult.getLocalTransactionState()).thenReturn(LocalTransactionState.COMMIT_MESSAGE);
        when(mq.sendMessageInTransaction(any(), any(), any())).thenReturn(sendResult);

        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl(
                mock(ISeckillVoucherService.class),
                mock(IOrderCloseService.class),
                mock(OrderOutboxMapper.class),
                idWorker,
                mock(StringRedisTemplate.class),
                mq,
                new ObjectMapper());
        loginAs(1010L);
        try {
            assertThat(service.seckillVoucher(15L).getData())
                    .isEqualTo("639250219233445879");
            assertThat(new ObjectMapper().writeValueAsString(new VoucherOrder().setId(orderId)))
                    .contains("\"id\":\"639250219233445879\"");
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void transactionCheckOnlyInterpretsRecordedResultsAndRetriesOnUnknown() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        // 故障注入在单测里保持全关，只验证回查的判定树。
        var listener = new OrderTransactionListener(redis, new ObjectMapper(),
                new FaultInjector(false, false, false, false, 0));
        var message = MessageBuilder.withPayload("{\"orderId\":9,\"userId\":1,\"voucherId\":1}").build();

        // 预占成功 -> 提交
        when(valueOps.get("seckill:tx:9")).thenReturn("SUCCESS");
        assertThat(listener.checkLocalTransaction(message))
                .isEqualTo(RocketMQLocalTransactionState.COMMIT);

        // 明确业务拒绝 -> 回滚
        when(valueOps.get("seckill:tx:9")).thenReturn("REJECTED");
        assertThat(listener.checkLocalTransaction(message))
                .isEqualTo(RocketMQLocalTransactionState.ROLLBACK);

        // 无记录：可能是慢回调，不能冒然回滚
        when(valueOps.get("seckill:tx:9")).thenReturn(null);
        assertThat(listener.checkLocalTransaction(message))
                .isEqualTo(RocketMQLocalTransactionState.UNKNOWN);

        // Redis 不可访问：同样按未知处理，绝不当成业务拒绝
        when(valueOps.get("seckill:tx:9")).thenThrow(new IllegalStateException("redis down"));
        assertThat(listener.checkLocalTransaction(message))
                .isEqualTo(RocketMQLocalTransactionState.UNKNOWN);
    }

    private static Integer stockOf(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=1", Integer.class);
    }

    private static Integer orderCountOf(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class);
    }

    private static void verifyOrderLifecycle(IOrderCloseService closeService,
                                             VoucherOrderServiceImpl service,
                                             JdbcTemplate jdbc) throws Exception {
        jdbc.update("INSERT INTO tb_seckill_voucher VALUES (2, 5), (3, 2)");

        // 支付胜出：订单进入已支付，库存和释放 Outbox 都不能被关单路径改动。
        service.createVoucherOrder(new SeckillOrderMessage(10L, 10L, 2L));
        loginAs(10L);
        try {
            assertThat(service.payOrder(10L).getSuccess()).isTrue();
        } finally {
            UserHolder.removeUser();
        }
        assertThat(orderStatusOf(jdbc, 10L)).isEqualTo(OrderStatus.PAID);
        assertThat(closeService.closeOrder(10L, false)).isFalse();
        assertThat(stockOf(jdbc, 2L)).isEqualTo(4);
        assertThat(outboxCountOf(jdbc, 10L, "STOCK_RELEASE")).isZero();

        // 主动取消只迁移一次：数据库库存只归还一次，释放 Outbox 也只写一条。
        service.createVoucherOrder(new SeckillOrderMessage(11L, 11L, 2L));
        loginAs(11L);
        try {
            assertThat(service.cancelOrder(11L).getSuccess()).isTrue();
            assertThat(service.cancelOrder(11L).getSuccess()).isFalse();
        } finally {
            UserHolder.removeUser();
        }
        assertThat(orderStatusOf(jdbc, 11L)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stockOf(jdbc, 2L)).isEqualTo(4);
        assertThat(outboxCountOf(jdbc, 11L, "STOCK_RELEASE")).isEqualTo(1);

        // 未到期不能超时关单；到期后支付失败，再由关单事务统一回收库存。
        service.createVoucherOrder(new SeckillOrderMessage(12L, 12L, 2L));
        assertThat(closeService.closeOrder(12L, true)).isFalse();
        jdbc.update("UPDATE tb_voucher_order SET pay_deadline=? WHERE id=12",
                LocalDateTime.now().minusSeconds(1));
        loginAs(12L);
        try {
            assertThat(service.payOrder(12L).getSuccess()).isFalse();
        } finally {
            UserHolder.removeUser();
        }
        assertThat(orderStatusOf(jdbc, 12L)).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(closeService.closeOrder(12L, true)).isTrue();
        assertThat(closeService.closeOrder(12L, true)).isFalse();
        assertThat(stockOf(jdbc, 2L)).isEqualTo(4);
        assertThat(outboxCountOf(jdbc, 12L, "STOCK_RELEASE")).isEqualTo(1);

        // 支付与取消同时竞争，必须且只能有一个状态迁移胜者。
        service.createVoucherOrder(new SeckillOrderMessage(13L, 13L, 2L));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var pay = executor.submit(() -> {
                loginAs(13L);
                ready.countDown();
                start.await();
                try {
                    return service.payOrder(13L).getSuccess();
                } finally {
                    UserHolder.removeUser();
                }
            });
            var close = executor.submit(() -> {
                ready.countDown();
                start.await();
                return closeService.closeOrder(13L, false);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            boolean payWon = pay.get(5, TimeUnit.SECONDS);
            boolean closeWon = close.get(5, TimeUnit.SECONDS);
            assertThat(payWon ^ closeWon).isTrue();
            assertThat(orderStatusOf(jdbc, 13L))
                    .isEqualTo(payWon ? OrderStatus.PAID : OrderStatus.CANCELLED);
            assertThat(stockOf(jdbc, 2L)).isEqualTo(payWon ? 3 : 4);
            assertThat(outboxCountOf(jdbc, 13L, "STOCK_RELEASE"))
                    .isEqualTo(closeWon ? 1 : 0);
        }

        // 取消释放购买资格：历史取消单保留，同一用户可用新 orderId 再次购买。
        service.createVoucherOrder(new SeckillOrderMessage(20L, 20L, 3L));
        loginAs(20L);
        try {
            assertThat(service.cancelOrder(20L).getSuccess()).isTrue();
        } finally {
            UserHolder.removeUser();
        }
        service.createVoucherOrder(new SeckillOrderMessage(21L, 20L, 3L));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE user_id=20 AND voucher_id=3",
                Integer.class)).isEqualTo(2);
        assertThat(stockOf(jdbc, 3L)).isEqualTo(1);
        // 新订单未取消前，第三张订单仍会被条件唯一索引拒绝。
        assertThatThrownBy(() -> service.createVoucherOrder(new SeckillOrderMessage(22L, 20L, 3L)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(stockOf(jdbc, 3L)).isEqualTo(1);

        // Outbox 不可写时，关单状态和库存归还必须一起回滚；建单也必须整体回滚。
        jdbc.update("INSERT INTO tb_seckill_voucher VALUES (4, 1), (5, 1)");
        service.createVoucherOrder(new SeckillOrderMessage(30L, 30L, 4L));
        jdbc.execute("DROP TABLE tb_order_outbox");
        assertThatThrownBy(() -> closeService.closeOrder(30L, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("释放 Outbox 写入失败");
        assertThat(orderStatusOf(jdbc, 30L)).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(stockOf(jdbc, 4L)).isZero();

        assertThatThrownBy(() -> service.createVoucherOrder(new SeckillOrderMessage(31L, 31L, 5L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("关单提醒 Outbox 写入失败");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE id=31", Integer.class)).isZero();
        assertThat(stockOf(jdbc, 5L)).isEqualTo(1);
    }

    private static void loginAs(long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
    }

    private static Integer stockOf(JdbcTemplate jdbc, long voucherId) {
        return jdbc.queryForObject(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucherId);
    }

    private static Integer orderStatusOf(JdbcTemplate jdbc, long orderId) {
        return jdbc.queryForObject(
                "SELECT status FROM tb_voucher_order WHERE id=?", Integer.class, orderId);
    }

    private static Integer outboxCountOf(JdbcTemplate jdbc, long orderId, String type) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_order_outbox WHERE order_id=? AND type=?",
                Integer.class, orderId, type);
    }
}

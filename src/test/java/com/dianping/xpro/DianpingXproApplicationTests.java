package com.dianping.xpro;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dianping.xpro.entity.Shop;
import com.dianping.xpro.mapper.ShopMapper;
import com.dianping.xpro.mq.FaultInjector;
import com.dianping.xpro.mq.OrderTransactionListener;
import com.dianping.xpro.mq.SeckillOrderMessage;
import com.dianping.xpro.service.impl.VoucherOrderServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
                                  pay_type INT, status INT, create_time TIMESTAMP, pay_time TIMESTAMP,
                                  use_time TIMESTAMP, refund_time TIMESTAMP, update_time TIMESTAMP,
                                  UNIQUE (user_id, voucher_id)
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
                    });
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
                new FaultInjector(false, false, false, 0));
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
}

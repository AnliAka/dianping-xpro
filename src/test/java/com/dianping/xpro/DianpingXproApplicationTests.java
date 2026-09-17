package com.dianping.xpro;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dianping.xpro.entity.Shop;
import com.dianping.xpro.entity.VoucherOrder;
import com.dianping.xpro.mapper.ShopMapper;
import com.dianping.xpro.service.impl.VoucherOrderServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DianpingXproApplicationTests {
    @Test
    void boot3SupportsPaginationMvcRedisConfigurationAndTransactionalSelfInvocation() {
        // No ApplicationReadyEvent is published by the runner: the Redis stream
        // consumer stays stopped. Redisson's network connection is mocked.
        try (MockedStatic<Redisson> redisson = mockStatic(Redisson.class)) {
            redisson.when(() -> Redisson.create(any(Config.class))).thenReturn(mock(RedissonClient.class));
            new WebApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(DianpingXproApplication.class)
                    .withPropertyValues(
                            "spring.config.import=",
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
                        jdbc.execute("CREATE TABLE tb_voucher_order (id BIGINT PRIMARY KEY, user_id BIGINT, voucher_id BIGINT)");
                        jdbc.update("INSERT INTO tb_seckill_voucher VALUES (1, 2)");
                        VoucherOrderServiceImpl service = context.getBean(VoucherOrderServiceImpl.class);
                        VoucherOrderServiceImpl target = AopTestUtils.getUltimateTargetObject(service);
                        VoucherOrderServiceImpl selfProxy = (VoucherOrderServiceImpl) ReflectionTestUtils.getField(target, "proxy");
                        assertThat(selfProxy).isNotNull();
                        VoucherOrder order = new VoucherOrder();
                        order.setId(1L);
                        order.setUserId(1L);
                        order.setVoucherId(1L);
                        selfProxy.createVoucherOrder(order);
                        assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=1", Integer.class))
                                .isEqualTo(1);
                        assertThatThrownBy(() -> selfProxy.createVoucherOrder(order)).isInstanceOf(DuplicateKeyException.class);
                        assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=1", Integer.class))
                                .as("failed order insert must roll back the stock decrement").isEqualTo(1);
                    });
        }
    }

    @Test
    void existingStreamGroupIsAcceptedButOtherRedisFailuresArePropagated() {
        var service = new VoucherOrderServiceImpl();
        var redis = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        try {
            var existingGroup = new RedisSystemException("Error in execution",
                    new IllegalStateException("BUSYGROUP Consumer Group name already exists"));
            doThrow(existingGroup).when(redis).execute(any(RedisCallback.class));
            assertThatCode(() -> ReflectionTestUtils.invokeMethod(service, "createStreamGroup"))
                    .doesNotThrowAnyException();
            var connectionFailure = new RedisSystemException("Connection failed", null);
            doThrow(connectionFailure).when(redis).execute(any(RedisCallback.class));
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "createStreamGroup"))
                    .isSameAs(connectionFailure);
        } finally {
            service.shutdown();
        }
    }
}

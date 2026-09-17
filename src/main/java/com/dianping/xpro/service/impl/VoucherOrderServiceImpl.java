package com.dianping.xpro.service.impl;

import com.dianping.xpro.entity.VoucherOrder;
import com.dianping.xpro.mapper.VoucherOrderMapper;
import com.dianping.xpro.service.ISeckillVoucherService;
import com.dianping.xpro.service.IVoucherOrderService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import com.dianping.xpro.dto.Result;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.dianping.xpro.utils.RedisIdWorker;
import com.dianping.xpro.utils.UserHolder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    @Lazy
    private VoucherOrderServiceImpl proxy;
    // @Autowired
    // private VoucherOrderServiceImpl proxy;
    private static final DefaultRedisScript<Long> seckillScript = new DefaultRedisScript<>();
    static {
        seckillScript.setLocation(new ClassPathResource("seckill.lua"));
        seckillScript.setResultType(Long.class);
    }
    private static final String STREAM_ORDERS = "stream.orders";
    private static final String GROUP_NAME = "g1";
    private static final String CONSUMER_NAME = "c1";
    private final ExecutorService seckill_order_handler = Executors.newSingleThreadExecutor();
    // 创建线程任务
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, CONSUMER_NAME),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(STREAM_ORDERS, ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    VoucherOrder voucherOrder = mapToVoucherOrder(record.getValue());
                    // 5. 生成订单
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDERS, GROUP_NAME, record.getId());
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    e.printStackTrace();
                    handlePendingList();
                }
            }
        }
    }
    // Start after the application context and transactional proxy are ready.
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        createStreamGroup();
        seckill_order_handler.submit(new VoucherOrderHandler());
    }
    @PreDestroy
    public void shutdown() {
        seckill_order_handler.shutdownNow();
    }
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
         proxy.createVoucherOrder(voucherOrder);
    }
    private void handlePendingList() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                 List<MapRecord<String,Object,Object>> list = stringRedisTemplate.opsForStream().read(
                                     Consumer.from(GROUP_NAME, CONSUMER_NAME),
                                     StreamReadOptions.empty().count(1),
                                     StreamOffset.create(STREAM_ORDERS, ReadOffset.from("0"))
                                 );

                if (list == null || list.isEmpty()) {
                    break;
                }
                MapRecord<String, Object, Object> record = list.get(0);
                VoucherOrder voucherOrder = mapToVoucherOrder(record.getValue());
                handleVoucherOrder(voucherOrder);
                stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDERS, GROUP_NAME, record.getId());
            } catch (Exception e) {
                e.printStackTrace();
                break;
            }
        }
    }
    private VoucherOrder mapToVoucherOrder(Map<Object, Object> value) {
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(Long.valueOf(value.get("id").toString()));
        voucherOrder.setUserId(Long.valueOf(value.get("userId").toString()));
        voucherOrder.setVoucherId(Long.valueOf(value.get("voucherId").toString()));
        return voucherOrder;
    }
    private void createStreamGroup() {
        try {
            stringRedisTemplate.execute((RedisCallback<Object>) connection -> {
                connection.execute(
                    "XGROUP",
                    "CREATE".getBytes(StandardCharsets.UTF_8),
                    STREAM_ORDERS.getBytes(StandardCharsets.UTF_8),
                    GROUP_NAME.getBytes(StandardCharsets.UTF_8),
                    "0".getBytes(StandardCharsets.UTF_8),
                    "MKSTREAM".getBytes(StandardCharsets.UTF_8)
                );
                return null;
            });
        } catch (RedisSystemException e) {
            // Spring Data Redis may wrap BUSYGROUP in the cause of this exception.
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) {
                    return;
                }
            }
            throw e;
        }
    }
    // @Override
    // @Override
    // public Result seckillVoucher(Long voucherId) {
    //     // 1. 获取秒杀券信息
    //     SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
    //     // 2. 判断秒杀卷是否可用
    //     if (seckillVoucher == null) {
    //         return Result.fail("秒杀券不存在");
    //     }
    //     if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
    //         return Result.fail("秒杀未开始");
    //     }
    //     if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
    //         return Result.fail("秒杀已结束");
    //     }
    //     if(seckillVoucher.getStock() <= 0) {
    //         return Result.fail("秒杀券已售罄");
    //     }
    //     Long userId = UserHolder.getUser().getId();
    //     RLock lock = redissonClient.getLock("lock:order:" + userId);
    //     boolean lockResult = lock.tryLock();
    //     if (!lockResult) {
    //         return Result.fail("请稍后再试");
    //     }
    //     try {
    //         return proxy.createVoucherOrder(voucherId);
    //     } finally {
    //         lock.unlock();
    //     }
    // }
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(seckillScript, new ArrayList<>(), voucherId.toString(), userId.toString(), String.valueOf(orderId));
        if(result == 0) {
            // 5. 返回订单信息
            return Result.ok(orderId);
        }
        if(result == 1) {
            return Result.fail("秒杀券已售罄");
        }
        if(result == 2) {
            return Result.fail("用户已购买该秒杀券");
        }
        return Result.fail("秒杀失败");
    }

    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 3. 可用的话减少库存
        // 下面这两行会带来并发问题，先查后直接更新
        // seckillVoucher.setStock(seckillVoucher.getStock() - 1);
        // seckillVoucherService.updateById(seckillVoucher);
        boolean success = seckillVoucherService.update()   //修改第一张表
            .setSql("stock = stock - 1")       // 【核心1】数据库原子扣减
            .eq("voucher_id", voucherOrder.getVoucherId())
            .gt("stock", 0)            // 【核心2】判断库存是否充足
            .update();
        if (!success) {
            return;
        }
        save(voucherOrder);
    }
}

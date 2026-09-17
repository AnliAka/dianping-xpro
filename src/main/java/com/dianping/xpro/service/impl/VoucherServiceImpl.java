package com.dianping.xpro.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.dianping.xpro.dto.Result;
import com.dianping.xpro.entity.Voucher;
import com.dianping.xpro.mapper.VoucherMapper;
import com.dianping.xpro.entity.SeckillVoucher;
import com.dianping.xpro.service.ISeckillVoucherService;
import com.dianping.xpro.service.IVoucherService;
import com.dianping.xpro.utils.RedisConstants;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    /** 优惠券类型：秒杀券。0=普通券，1=秒杀券。 */
    private static final int VOUCHER_TYPE_SECKILL = 1;

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 补建可能缺失的秒杀库存（Redis 重启、数据用 SQL 直接导入等情况）
        preheatSeckillStock(vouchers);
        // 返回结果
        return Result.ok(vouchers);
    }

    /**
     * 兜底预热秒杀库存。
     * <p>
     * 正常路径下库存由 {@link #addSeckillVoucher} 写入，但秒杀券如果是直接用 SQL
     * 导入的，就绕过了那段逻辑，Redis 里没有 {@code seckill:stock:{id}} 这个键，
     * 抢购时 lua 脚本会判定为「库存不足」，前端表现为「秒杀券已售罄」。
     * 这里在每次查询店铺券列表时补建缺失的键。
     */
    private void preheatSeckillStock(List<Voucher> vouchers) {
        if (vouchers == null || vouchers.isEmpty()) {
            return;
        }
        for (Voucher voucher : vouchers) {
            if (voucher.getType() == null
                    || voucher.getType() != VOUCHER_TYPE_SECKILL
                    || voucher.getStock() == null) {
                continue;
            }
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucher.getId();
            // setIfAbsent 保证不会覆盖已经被 lua 扣减过的实时库存
            stringRedisTemplate.opsForValue()
                    .setIfAbsent(stockKey, String.valueOf(voucher.getStock()));
        }
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        // 保存秒杀库存到redis中
        stringRedisTemplate.opsForValue()
                .set(RedisConstants.SECKILL_STOCK_KEY + voucher.getId(),
                        String.valueOf(voucher.getStock()));
    }
}

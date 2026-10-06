package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.BloomFilterUtils;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.BLOOM_FILTER_VOUCHER;
import static com.hmdp.utils.RedisConstants.CACHE_VOUCHER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_META_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private BloomFilterUtils bloomFilterUtils;



    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
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

        //保存秒杀库存到redis
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());

        // 登记布隆过滤器，避免缓存查询被前置拦截
        bloomFilterUtils.add(BLOOM_FILTER_VOUCHER, CACHE_VOUCHER_KEY + voucher.getId());

        // 写入秒杀时间窗口元数据（epoch 毫秒），供秒杀 Lua 原子校验；无时间限制时仅写库存
        if (voucher.getBeginTime() != null || voucher.getEndTime() != null) {
            Map<String, String> meta = new HashMap<>();
            if (voucher.getBeginTime() != null) {
                meta.put("begin", String.valueOf(toEpochMilli(voucher.getBeginTime())));
            }
            if (voucher.getEndTime() != null) {
                meta.put("end", String.valueOf(toEpochMilli(voucher.getEndTime())));
            }
            stringRedisTemplate.opsForHash().putAll(SECKILL_META_KEY + voucher.getId(), meta);
        }
    }

    /**
     * 将 LocalDateTime 按系统默认时区转换为 epoch 毫秒，与 System.currentTimeMillis() 可比。
     */
    private static long toEpochMilli(java.time.LocalDateTime dateTime) {
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}

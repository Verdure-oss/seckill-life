package com.hmdp.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mapper.VoucherMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 布隆过滤器预热：服务启动时把已存在的店铺/优惠券 id 批量写入 Redis 布隆过滤器，
 * 保证重启后过滤器不丢失、且缓存穿透拦截不漏放已存在的数据。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "bloom.warmup.enabled", havingValue = "true", matchIfMissing = true)
public class BloomFilterWarmup implements ApplicationRunner {

    private final BloomFilterUtils bloomFilterUtils;
    private final ShopMapper shopMapper;
    private final VoucherMapper voucherMapper;

    public BloomFilterWarmup(BloomFilterUtils bloomFilterUtils, ShopMapper shopMapper, VoucherMapper voucherMapper) {
        this.bloomFilterUtils = bloomFilterUtils;
        this.shopMapper = shopMapper;
        this.voucherMapper = voucherMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            warmupShops();
            warmupVouchers();
        } catch (Exception e) {
            // 预热失败不影响服务启动（查询会退化为直查数据库）
            log.warn("布隆过滤器预热失败（可忽略，不影响服务启动）", e);
        }
    }

    private void warmupShops() {
        List<Object> ids = shopMapper.selectObjs(new QueryWrapper<Shop>().select("id"));
        for (Object id : ids) {
            bloomFilterUtils.add(RedisConstants.BLOOM_FILTER_SHOP, RedisConstants.CACHE_SHOP_KEY + id);
        }
        log.info("店铺布隆过滤器预热完成，共 {} 条", ids.size());
    }

    private void warmupVouchers() {
        List<Object> ids = voucherMapper.selectObjs(new QueryWrapper<Voucher>().select("id"));
        for (Object id : ids) {
            bloomFilterUtils.add(RedisConstants.BLOOM_FILTER_VOUCHER, RedisConstants.CACHE_VOUCHER_KEY + id);
        }
        log.info("优惠券布隆过滤器预热完成，共 {} 条", ids.size());
    }
}
package com.hmdp.utils;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Redis（Redisson）的分布式布隆过滤器工具，用于缓存穿透前置拦截。
 * <p>
 * 相比 JVM 内存版（Guava）：多实例共享同一份过滤结果、重启不丢失（位图持久化在 Redis 中），
 * 但首次使用前需要预热，把已存在的数据（店铺/优惠券 id）批量写入过滤器。
 * </p>
 */
@Component
public class BloomFilterUtils {

    /** 预估插入数量 */
    private static final long EXPECTED_INSERTIONS = 1_000_000L;

    /** 允许的误判率 */
    private static final double FPP = 0.001;

    private final RedissonClient redissonClient;
    private final Map<String, RBloomFilter<String>> filters = new ConcurrentHashMap<>();

    public BloomFilterUtils(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 获取指定名称的布隆过滤器，首次访问时惰性初始化。
     */
    public RBloomFilter<String> getFilter(String name) {
        return filters.computeIfAbsent(name, n -> {
            RBloomFilter<String> filter = redissonClient.getBloomFilter(n);
            // tryInit 幂等：已存在的过滤器配置不会被重建
            filter.tryInit(EXPECTED_INSERTIONS, FPP);
            return filter;
        });
    }

    /**
     * 向指定名称的布隆过滤器添加一个 key。
     */
    public void add(String name, String key) {
        getFilter(name).add(key);
    }

    /**
     * 判断 key 是否可能存在。
     *
     * @return true 表示可能存在；false 表示一定不存在
     */
    public boolean mightContain(String name, String key) {
        return getFilter(name).contains(key);
    }
}
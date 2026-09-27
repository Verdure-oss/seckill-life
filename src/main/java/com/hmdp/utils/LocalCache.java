package com.hmdp.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 本地缓存封装 (基于 Caffeine)
 * <p>
 * 用于实现多级缓存架构中的本地缓存层。
 * 热点数据可直接命中本地缓存，省去 Redis 网络往返，降低 Redis 压力。
 * </p>
 */
@Component
public class LocalCache<K, V> {

    private final Cache<K, V> cache;

    public LocalCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000) // 最大容量 10000 条
                .expireAfterWrite(2, TimeUnit.MINUTES) // 写入后 2 分钟过期（短 TTL 兜底）
                .recordStats() // 开启统计便于监控
                .build();
    }

    /**
     * 写入本地缓存
     */
    public void put(K key, V value) {
        cache.put(key, value);
    }

    /**
     * 读取本地缓存
     */
    public V get(K key) {
        return cache.getIfPresent(key);
    }

    /**
     * 主动失效本地缓存
     */
    public void evict(K key) {
        cache.invalidate(key);
    }

    /**
     * 获取缓存统计信息
     */
    public Object getStats() {
        return cache.stats();
    }
}

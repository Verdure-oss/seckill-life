package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final LocalCache<String, String> localCache;

    public CacheClient(StringRedisTemplate stringRedisTemplate, LocalCache<String, String> localCache) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.localCache = localCache;
    }

    /**
     * 设置缓存，自动加入随机因子防止缓存雪崩。
     * @param key 键
     * @param value 值
     * @param minTtl 最小TTL（单位由 unit 指定）
     * @param maxTtl 最大TTL（单位由 unit 指定）
     * @param unit 时间单位
     */
    public void setRandomTTL(String key, Object value, Long minTtl, Long maxTtl, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), randomTTL(minTtl, maxTtl), unit);
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 在 [minTtl, maxTtl] 范围内随机生成一个 TTL。
     */
    private static Long randomTTL(Long minTtl, Long maxTtl) {
        if (minTtl.equals(maxTtl)) {
            return minTtl;
        }
        return minTtl + (long)(Math.random() * (maxTtl - minTtl + 1));
    }

    public void setWithLogicalExpire(String key, Object value, Long minTime, Long maxTime, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        long randomExpireSeconds = randomTTL(minTime, maxTime);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(randomExpireSeconds));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透查询
     * @param id
     * @return
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit,
            String bloomFilterName) {
        String key = keyPrefix + id;

        String json = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        if (json != null) {
            // 缓存命中空值，直接返回 null
            return null;
        }

        // 布隆过滤器拦截：如果 key 可能不存在于任何数据源中，则无需调用 DB
        if (bloomFilterName != null) {
            if (!BloomFilterUtils.mightContain(bloomFilterName, key)) {
                return null;
            }
        }

        // 正式查数据库
        R r = dbFallback.apply(id);
        if (r == null) {
            // 缓存穿透优化：缓存空值以防后续重复穿透查询
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        this.set(key, r, time, unit);
        return r;
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 逻辑过期查询店铺详情
     * @param id
     * @return
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit,
            String bloomFilterName) {
        String key = keyPrefix + id;

        String json = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isBlank(json)) {
            // 布隆过滤器拦截：如果 key 可能不存在，则无需执行逻辑过期查询
            if (bloomFilterName != null && !BloomFilterUtils.mightContain(bloomFilterName, key)) {
                return null;
            }
            return null;
        }

        //命中反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //未过期返回信息
            return r;
        }

        //过期，缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r1, time - 3, time + 3, unit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }

        return r;
    }

    /**
     * 多级缓存查询 (Caffeine + Redis)
     * <p>
     * 查询逻辑:
     * 1. 本地缓存命中 → 直接返回
     * 2. Redis 缓存命中 → 回填本地缓存并返回
     * 3. 布隆过滤器判断是否穿透 → 若可能不存在，跳过 DB 查询
     * 4. 查询数据库 → 回写双层缓存
     * </p>
     * @param keyPrefix 缓存键前缀
     * @param id 主键 ID
     * @param type 返回类型
     * @param dbFallback 数据库查询回调
     * @param time 缓存 TTL
     * @param unit 时间单位
     * @param bloomFilterName 布隆过滤器名称
     * @return 查询结果
     */
    public <R, ID> R queryWithMultiLevelCache(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit,
            String bloomFilterName) {
        String key = keyPrefix + id;

        // 1. 本地缓存命中
        String localJson = localCache.get(key);
        if (StrUtil.isNotBlank(localJson)) {
            log.debug("本地缓存命中: {}", key);
            return JSONUtil.toBean(localJson, type);
        }

        // 2. Redis 缓存命中
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            log.debug("Redis缓存命中: {}", key);
            // 回填本地缓存
            localCache.put(key, json);
            return JSONUtil.toBean(json, type);
        }
        // 缓存空值处理
        if (json != null) {
            return null;
        }

        // 3. 布隆过滤器拦截
        if (bloomFilterName != null && !BloomFilterUtils.mightContain(bloomFilterName, key)) {
            log.debug("布隆过滤器拦截: {}", key);
            return null;
        }

        // 4. 数据库查询
        R r = dbFallback.apply(id);
        if (r == null) {
            // 缓存空值防止穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 回写双层缓存
        String jsonStr = JSONUtil.toJsonStr(r);
        localCache.put(key, jsonStr);
        this.setWithRandomTTL(key, r, time - 3, time + 3, unit);

        return r;
    }

    /**
     * 设置带随机 TTL 的缓存 (用于多级缓存回写)
     */
    private void setWithRandomTTL(String key, Object value, Long minTime, Long maxTime, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), randomTTL(minTime, maxTime), unit);
    }

    /**
     * 清除多级缓存 (用于更新场景)
     */
    public void evictMultiLevel(String keyPrefix, Object id) {
        String key = keyPrefix + id;
        // 清除本地缓存
        localCache.evict(key);
        // 清除 Redis 缓存
        stringRedisTemplate.delete(key);
        // 广播失效消息
        stringRedisTemplate.convertAndSend("cache:invalidate", key);
    }

    /**
     * 尝试获取互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


}

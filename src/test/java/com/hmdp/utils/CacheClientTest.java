package com.hmdp.utils;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：CacheClient 逻辑过期缓存的关键分支，使用 mock 隔离 Redis。
 * 覆盖：未过期直接返回、过期后异步重建但旧值先返回。
 */
class CacheClientTest {

    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOps;
    private CacheClient cacheClient;

    @BeforeEach
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

        cacheClient = new CacheClient(stringRedisTemplate);
    }

    private Shop buildShop(Long id) {
        Shop shop = new Shop();
        shop.setId(id);
        shop.setName("shop-" + id);
        return shop;
    }

    /** 未过期：命中缓存直接返回，不触发重建 */
    @Test
    void queryWithLogicalExpire_notExpired_returnsCachedValueWithoutRebuild() {
        // given 缓存中有未过期的数据
        Shop cached = buildShop(1L);
        RedisData redisData = new RedisData();
        redisData.setData(cached);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(5));
        when(valueOps.get("cache:shop:1")).thenReturn(JSONUtil.toJsonStr(redisData));

        // dbFallback 不应被调用
        Function<Long, Shop> dbFallback = id -> {
            throw new AssertionError("未过期时不应访问数据库");
        };

        // when
        Shop result = cacheClient.queryWithLogicalExpire("cache:shop:", 1L, Shop.class, dbFallback, 30L, TimeUnit.MINUTES, RedisConstants.BLOOM_FILTER_SHOP);

        // then
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("shop-1");
        // 未过期，不应触发缓存重建写回
        verify(valueOps, never()).set(any(String.class), any(String.class), anyLong(), any(TimeUnit.class));
    }

    /** 缓存空缺：直接返回 null，不查库（逻辑过期策略下由预热保证命中） */
    @Test
    void queryWithLogicalExpire_cacheMiss_returnsNullWithoutRebuild() {
        when(valueOps.get("cache:shop:2")).thenReturn(null);

        Function<Long, Shop> dbFallback = id -> {
            throw new AssertionError("逻辑过期策略不应直接查库");
        };

        Shop result = cacheClient.queryWithLogicalExpire("cache:shop:", 2L, Shop.class, dbFallback, 30L, TimeUnit.MINUTES, RedisConstants.BLOOM_FILTER_SHOP);

        assertThat(result).isNull();
    }

    /** 已过期：先返回旧值，同时触发一次异步重建（写入带新过期时间） */
    @Test
    void queryWithLogicalExpire_expired_returnsStaleValueAndTriggersRebuild() throws InterruptedException {
        // given 缓存中有已过期的数据
        Shop stale = buildShop(3L);
        RedisData redisData = new RedisData();
        redisData.setData(stale);
        redisData.setExpireTime(LocalDateTime.now().minusSeconds(1));
        when(valueOps.get("cache:shop:3")).thenReturn(JSONUtil.toJsonStr(redisData));
        // 互斥锁可获取
        when(valueOps.setIfAbsent("lock:shop:3", "1", 10L, TimeUnit.SECONDS)).thenReturn(true);

        // 重建回调：从数据库取新数据
        Function<Long, Shop> dbFallback = id -> buildShop(30L);

        // when
        Shop result = cacheClient.queryWithLogicalExpire("cache:shop:", 3L, Shop.class, dbFallback, 30L, TimeUnit.MINUTES, RedisConstants.BLOOM_FILTER_SHOP);

        // then 先返回旧值
        assertThat(result.getId()).isEqualTo(3L);

        // 等待异步重建线程执行完毕
        Thread.sleep(200);

        // 重建应写入带新过期时间的缓存；验证调用过 set
        verify(stringRedisTemplate).delete((String) "lock:shop:3");
    }

    /** 过期但互斥锁已被占用：返回旧值，且不触发重复重建 */
    @Test
    void queryWithLogicalExpire_expiredAndLockHeld_returnsStaleWithoutRebuild() throws InterruptedException {
        Shop stale = buildShop(4L);
        RedisData redisData = new RedisData();
        redisData.setData(stale);
        redisData.setExpireTime(LocalDateTime.now().minusSeconds(1));
        when(valueOps.get("cache:shop:4")).thenReturn(JSONUtil.toJsonStr(redisData));
        // 锁未获取到
        when(valueOps.setIfAbsent(eq("lock:shop:4"), eq("1"), anyLong(), any(TimeUnit.class))).thenReturn(false);

        Function<Long, Shop> dbFallback = id -> buildShop(40L);

        Shop result = cacheClient.queryWithLogicalExpire("cache:shop:", 4L, Shop.class, dbFallback, 30L, TimeUnit.MINUTES, RedisConstants.BLOOM_FILTER_SHOP);

        assertThat(result.getId()).isEqualTo(4L);
        Thread.sleep(200);
        // 未获取锁，不应写库回调重建（不调用 set 带新 TTL）
        verify(stringRedisTemplate, never()).delete(anyString());
    }
}
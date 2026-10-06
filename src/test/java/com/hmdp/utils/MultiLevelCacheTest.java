package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 单元测试：CacheClient 多级缓存行为
 * 覆盖：本地缓存命中、Redis 回写、布隆过滤器拦截、缓存清除
 */
class MultiLevelCacheTest {

    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOps;
    private CacheClient cacheClient;
    private LocalCache<String, String> localCache;
    private BloomFilterUtils bloomFilterUtils;

    @BeforeEach
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

        localCache = new LocalCache<>();
        bloomFilterUtils = mock(BloomFilterUtils.class);
        cacheClient = new CacheClient(stringRedisTemplate, localCache, bloomFilterUtils);
    }

    private Shop buildShop(Long id) {
        Shop shop = new Shop();
        shop.setId(id);
        shop.setName("shop-" + id);
        return shop;
    }

    /** 缓存命中 Redis，直接回写本地缓存并返回 */
    @Test
    void queryWithMultiLevelCache_redisHit_returnsValueAndBackfillsLocalCache() {
        Shop cached = buildShop(1L);
        when(valueOps.get("cache:shop:1")).thenReturn(JSONUtil.toJsonStr(cached));

        Function<Long, Shop> dbFallback = id -> {
            throw new AssertionError("Redis 命中不应访问数据库");
        };

        Shop result = cacheClient.queryWithMultiLevelCache(
                "cache:shop:", 1L, Shop.class, dbFallback,
                30L, TimeUnit.MINUTES, RedisConstants.BLOOM_FILTER_SHOP);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("shop-1");
        // 本地缓存应被回写
        assertThat(localCache.get("cache:shop:1")).isNotBlank();
    }

    /** 第二次查询命中本地缓存，无需访问 Redis */
    @Test
    void queryWithMultiLevelCache_localCacheHit_returnsValueWithoutRedisAccess() {
        Shop cached = buildShop(2L);
        String json = JSONUtil.toJsonStr(cached);
        localCache.put("cache:shop:2", json);

        Function<Long, Shop> dbFallback = id -> {
            throw new AssertionError("本地缓存命中不应访问数据库");
        };

        Shop result = cacheClient.queryWithMultiLevelCache(
                "cache:shop:", 2L, Shop.class, dbFallback,
                30L, TimeUnit.MINUTES, RedisConstants.BLOOM_FILTER_SHOP);

        assertThat(result.getId()).isEqualTo(2L);
        // Redis 不应被访问
        verify(valueOps, never()).get(anyString());
    }

    /** 缓存空缺，布隆过滤器拦截，跳过数据库查询 */
    @Test
    void queryWithMultiLevelCache_bloomFilterRejects_returnsNull() {
        // Redis 缓存未命中
        when(valueOps.get("cache:shop:99999999")).thenReturn(null);
        // 布隆过滤器判定 key 一定不存在
        when(bloomFilterUtils.mightContain(RedisConstants.BLOOM_FILTER_SHOP, "cache:shop:99999999")).thenReturn(false);

        Function<Long, Shop> dbFallback = id -> {
            throw new AssertionError("布隆过滤器拦截不应访问数据库");
        };

        Shop result = cacheClient.queryWithMultiLevelCache(
                "cache:shop:", 99999999L, Shop.class, dbFallback,
                30L, TimeUnit.MINUTES, RedisConstants.BLOOM_FILTER_SHOP);

        assertThat(result).isNull();
        // Redis 查询应发生 (缓存未命中)
        verify(valueOps).get("cache:shop:99999999");
        // 数据库不应被调用
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    /** 缓存空缺，布隆过滤器放行，查数据库并回写 */
    @Test
    void queryWithMultiLevelCache_cacheMiss_dbHit_returnsValueAndCaches() {
        when(valueOps.get("cache:shop:3")).thenReturn(null);
        // 布隆过滤器放行
        when(bloomFilterUtils.mightContain(RedisConstants.BLOOM_FILTER_SHOP, "cache:shop:3")).thenReturn(true);

        Shop dbResult = buildShop(3L);
        Function<Long, Shop> dbFallback = id -> dbResult;

        Shop result = cacheClient.queryWithMultiLevelCache(
                "cache:shop:", 3L, Shop.class, dbFallback,
                30L, TimeUnit.MINUTES, RedisConstants.BLOOM_FILTER_SHOP);

        assertThat(result.getId()).isEqualTo(3L);
        // 应回写 Redis 缓存
        verify(valueOps).set(eq("cache:shop:3"), anyString(), anyLong(), any(TimeUnit.class));
    }

    /** 清除多级缓存方法测试 */
    @Test
    void evictMultiLevel_shouldClearBothLayers() {
        // 先填充本地缓存
        localCache.put("cache:shop:4", "test");
        assertThat(localCache.get("cache:shop:4")).isEqualTo("test");

        // 清除
        cacheClient.evictMultiLevel("cache:shop:", 4L);

        // 本地缓存应被清除
        assertThat(localCache.get("cache:shop:4")).isNull();
        // Redis delete 应被调用
        verify(stringRedisTemplate).delete("cache:shop:4");
        // 广播失效消息应被发送
        verify(stringRedisTemplate).convertAndSend(eq("cache:invalidate"), eq("cache:shop:4"));
    }
}
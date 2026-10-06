package com.hmdp.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：BloomFilterUtils 委托 Redisson 的 RBloomFilter（分布式布隆过滤器）。
 * 使用 mock 隔离真实 Redis。
 */
class BloomFilterUtilsTest {

    private RedissonClient redissonClient;
    private RBloomFilter<String> shopFilter;
    private RBloomFilter<String> voucherFilter;
    private BloomFilterUtils bloomFilterUtils;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        shopFilter = mock(RBloomFilter.class);
        voucherFilter = mock(RBloomFilter.class);
        doReturn(shopFilter).when(redissonClient).getBloomFilter(RedisConstants.BLOOM_FILTER_SHOP);
        doReturn(voucherFilter).when(redissonClient).getBloomFilter(RedisConstants.BLOOM_FILTER_VOUCHER);
        bloomFilterUtils = new BloomFilterUtils(redissonClient);
    }

    /** mightContain 应委托给 Redis 布隆过滤器 */
    @Test
    void mightContain_shouldDelegateToRedisFilter() {
        when(shopFilter.contains("cache:shop:1")).thenReturn(true);

        assertThat(bloomFilterUtils.mightContain(RedisConstants.BLOOM_FILTER_SHOP, "cache:shop:1")).isTrue();
        verify(shopFilter).contains("cache:shop:1");
    }

    /** 未加入的 key 应返回 false */
    @Test
    void mightContain_shouldReturnFalse_forUnknownKey() {
        when(shopFilter.contains(anyString())).thenReturn(false);

        assertThat(bloomFilterUtils.mightContain(
                RedisConstants.BLOOM_FILTER_SHOP, "cache:shop:nonexistent")).isFalse();
    }

    /** add 应委托给 Redis 布隆过滤器 */
    @Test
    void add_shouldDelegateToRedisFilter() {
        bloomFilterUtils.add(RedisConstants.BLOOM_FILTER_SHOP, "cache:shop:9");

        verify(shopFilter).add("cache:shop:9");
    }

    /** 不同名称的过滤器相互独立 */
    @Test
    void filtersShouldBeIsolated_perName() {
        when(shopFilter.contains("cache:shop:1")).thenReturn(true);
        // voucher 过滤器未被访问时，不应返回 shop 的判定结果
        assertThat(bloomFilterUtils.mightContain(RedisConstants.BLOOM_FILTER_SHOP, "cache:shop:1")).isTrue();
        verify(voucherFilter, never()).contains(anyString());
    }
}
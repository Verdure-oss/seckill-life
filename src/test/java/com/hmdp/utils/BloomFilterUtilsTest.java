package com.hmdp.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：BloomFilterUtils 提供的缓存穿透防护能力
 */
class BloomFilterUtilsTest {

    /**
     * 当 key 被 add 后，mightContain 应返回 true。
     * （BloomFilter 是「可能存在」判断，不能百分之百保证正确）
     */
    @Test
    void mightContain_shouldReturnTrue_afterAdd() {
        String filterName = RedisConstants.BLOOM_FILTER_SHOP;
        String key = "cache:shop:999";

        BloomFilterUtils.add(filterName, key);

        boolean result = BloomFilterUtils.mightContain(filterName, key);

        assertThat(result).isTrue();
    }

    /**
     * 未被 add 的 key 应极大概率返回 false。
     * 若偶尔返回 true，说明是误判（false positive），概率极低。
     */
    @Test
    void mightContain_shouldReturnFalse_forUnknownKey() {
        String filterName = RedisConstants.BLOOM_FILTER_SHOP;
        String unknownKey = "cache:shop:nonexistent_" + System.nanoTime();

        // 确保没有添加过这个 key
        boolean result = BloomFilterUtils.mightContain(filterName, unknownKey);

        // 理论上应为 false，BloomFilter 允许少量误判
        assertThat(result).isFalse();
    }

    /**
     * 确保不同 filterName 的过滤器相互独立。
     */
    @Test
    void filtersShouldBeIsolated_perName() {
        BloomFilterUtils.add(RedisConstants.BLOOM_FILTER_SHOP, "cache:shop:1");
        BloomFilterUtils.add(RedisConstants.BLOOM_FILTER_VOUCHER, "cache:voucher:1");

        boolean shopExists = BloomFilterUtils.mightContain(RedisConstants.BLOOM_FILTER_SHOP, "cache:shop:1");
        boolean voucherExists = BloomFilterUtils.mightContain(RedisConstants.BLOOM_FILTER_VOUCHER, "cache:voucher:1");

        assertThat(shopExists).isTrue();
        assertThat(voucherExists).isTrue();
    }
}
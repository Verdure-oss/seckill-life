package com.hmdp.utils;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：CacheClient 中的随机 TTL 策略
 * 验证 randomTTL(min, max) 生成的数值落在 [min, max] 区间内。
 */
class TTLStrategyTest {

    @Test
    void randomTTL_shouldBeWithinBounds() throws Exception {
        // 通过反射调用私有方法
        Method method = CacheClient.class.getDeclaredMethod("randomTTL", Long.class, Long.class);
        method.setAccessible(true);

        long min = 27L;
        long max = 33L;

        for (int i = 0; i < 1000; i++) {
            Long ttl = (Long) method.invoke(null, min, max);
            assertThat(ttl).isBetween(min, max);
        }
    }

    @Test
    void randomTTL_whenMinEqualsMax_returnsSameValue() throws Exception {
        Method method = CacheClient.class.getDeclaredMethod("randomTTL", Long.class, Long.class);
        method.setAccessible(true);

        Long ttl = (Long) method.invoke(null, 30L, 30L);
        assertThat(ttl).isEqualTo(30L);
    }
}
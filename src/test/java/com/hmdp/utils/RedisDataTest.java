package com.hmdp.utils;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：验证 RedisData 的 JSON 序列化/反序列化能正确往返，
 * 不依赖真实 Redis。
 */
class RedisDataTest {

    @Test
    void serialize_deserialize_roundTripPreservesDataAndExpire() {
        // given
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("测试店铺");
        RedisData data = new RedisData();
        data.setExpireTime(LocalDateTime.of(2026, 10, 1, 12, 0));
        data.setData(shop);

        // when
        String json = JSONUtil.toJsonStr(data);
        RedisData parsed = JSONUtil.toBean(json, RedisData.class);
        Shop parsedShop = JSONUtil.toBean((JSONObject) parsed.getData(), Shop.class);

        // then
        assertThat(parsed.getExpireTime()).isEqualTo(data.getExpireTime());
        assertThat(parsedShop.getId()).isEqualTo(shop.getId());
        assertThat(parsedShop.getName()).isEqualTo(shop.getName());
    }
}
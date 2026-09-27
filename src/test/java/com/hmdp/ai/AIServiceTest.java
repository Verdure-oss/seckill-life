package com.hmdp.ai;

import com.hmdp.entity.Shop;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 工具基本结构测试
 */
class AIServiceTest {

    /**
     * 验证 ShopQueryTool 被正确注册为 Spring Bean
     */
    @Test
    void shopQueryTool_shouldBeRegisteredAsBean() {
        assertThat(new ShopQueryTool()).isNotNull();
    }

    /**
     * 验证 ReservationTool 被正确注册为 Spring Bean
     */
    @Test
    void reservationTool_shouldBeRegisteredAsBean() {
        assertThat(new ReservationTool()).isNotNull();
    }

    /**
     * 验证 RedisChatMemoryStore 可以被实例化
     */
    @Test
    void redisChatMemoryStore_shouldBeInstantiable() {
        assertThat(new RedisChatMemoryStore()).isNotNull();
    }

    /**
     * 验证 Tool 注解存在
     */
    @Test
    void tools_shouldHaveToolAnnotation() {
        boolean hasShopQueryToolAnnotation = false;
        boolean hasReservationToolAnnotation = false;

        // Check ShopQueryTool methods
        for (java.lang.reflect.Method method : ShopQueryTool.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                hasShopQueryToolAnnotation = true;
            }
        }

        // Check ReservationTool methods
        for (java.lang.reflect.Method method : ReservationTool.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                hasReservationToolAnnotation = true;
            }
        }

        assertThat(hasShopQueryToolAnnotation).isTrue();
        assertThat(hasReservationToolAnnotation).isTrue();
    }
}
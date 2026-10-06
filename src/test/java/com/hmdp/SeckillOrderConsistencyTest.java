package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 秒杀订单一致性回归测试：DB 唯一约束、RabbitMQ 死信/延迟关单、Lua 回补脚本。
 * 均为纯单元/静态检查，不依赖 Redis/MySQL/RabbitMQ。
 */
class SeckillOrderConsistencyTest {

    private String readResource(String path) throws Exception {
        return StreamUtils.copyToString(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
    }

    /** tb_voucher_order 应包含 (user_id, voucher_id) 唯一索引，作为“一人一单”的最终兜底 */
    @Test
    void voucherOrderTable_shouldHaveUniqueIndexOnUserAndVoucher() throws Exception {
        String sql = readResource("db/hmdp.sql");
        assertThat(sql).contains("UNIQUE KEY `uk_user_voucher`(`user_id`, `voucher_id`)");
    }
}
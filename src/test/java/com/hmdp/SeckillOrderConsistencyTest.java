package com.hmdp;

import com.hmdp.config.RabbitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
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

    /** 秒杀订单队列应绑定死信交换机/路由键，消费重试耗尽后被路由到 DLQ */
    @Test
    void seckillOrderQueue_shouldBeBoundToDeadLetterExchange() {
        RabbitConfig config = new RabbitConfig();
        Queue queue = config.seckillOrderQueue();

        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", RabbitConfig.SECKILL_ORDER_DLX)
                .containsEntry("x-dead-letter-routing-key", RabbitConfig.SECKILL_ORDER_DLQ);
    }

    /** 死信交换机 / 死信队列 / 绑定关系应正确装配 */
    @Test
    void deadLetterTopology_shouldBeWired() {
        RabbitConfig config = new RabbitConfig();

        DirectExchange dlx = config.seckillOrderDeadLetterExchange();
        Queue dlq = config.seckillOrderDeadLetterQueue();
        Binding binding = config.seckillOrderDeadLetterBinding(dlq, dlx);

        assertThat(dlx.getName()).isEqualTo("seckill.order.dlx");
        assertThat(dlq.getName()).isEqualTo("seckill.order.dlq");
        assertThat(binding.getDestination()).isEqualTo("seckill.order.dlq");
        assertThat(binding.getExchange()).isEqualTo("seckill.order.dlx");
        assertThat(binding.getRoutingKey()).isEqualTo("seckill.order.dlq");
    }
}
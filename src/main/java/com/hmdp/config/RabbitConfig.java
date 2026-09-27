package com.hmdp.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for async seckill order processing.
 */
@Configuration
public class RabbitConfig {

    /**
     * Seckill order exchange name.
     */
    public static final String SECKILL_ORDER_EXCHANGE = "seckill.order.direct";

    /**
     * Seckill order queue name.
     */
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";

    /**
     * Routing key for seckill orders.
     */
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order.create";

    /**
     * Create a durable direct exchange.
     */
    @Bean
    public DirectExchange seckillOrderExchange() {
        return new DirectExchange(SECKILL_ORDER_EXCHANGE, true, false);
    }

    /**
     * Create a durable queue for seckill orders.
     */
    @Bean
    public Queue seckillOrderQueue() {
        return new Queue(SECKILL_ORDER_QUEUE, true);
    }

    /**
     * Bind the queue to the exchange with routing key.
     */
    @Bean
    public Binding seckillOrderBinding() {
        return BindingBuilder
                .bind(seckillOrderQueue())
                .to(seckillOrderExchange())
                .with(SECKILL_ORDER_ROUTING_KEY);
    }
}
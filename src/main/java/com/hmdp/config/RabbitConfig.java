package com.hmdp.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ configuration for async seckill order processing.
 *
 * <p>下单拓扑：</p>
 * <pre>
 * seckill.order.direct --&gt; seckill.order.queue （异步落库）
 *                                 |-- 消费重试耗尽 --&gt; seckill.order.dlx --&gt; seckill.order.dlq（死信，人工排查）
 * </pre>
 */
@Configuration
public class RabbitConfig {

    /*
     * 秒杀下单队列（异步落库）。
     */
    public static final String SECKILL_ORDER_EXCHANGE = "seckill.order.direct";
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order.create";

    /*
     * 死信交换 / 死信队列：下单消费重试耗尽后进入，避免消息无限重试或丢失。
     */
    public static final String SECKILL_ORDER_DLX = "seckill.order.dlx";
    public static final String SECKILL_ORDER_DLQ = "seckill.order.dlq";

    /**
     * Create a durable direct exchange for seckill orders.
     */
    @Bean
    public DirectExchange seckillOrderExchange() {
        return new DirectExchange(SECKILL_ORDER_EXCHANGE, true, false);
    }

    /**
     * Create a durable queue for seckill orders, bound to a dead-letter exchange
     * so that messages rejected after retry exhaustion are not lost.
     */
    @Bean
    public Queue seckillOrderQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", SECKILL_ORDER_DLX);
        args.put("x-dead-letter-routing-key", SECKILL_ORDER_DLQ);
        return new Queue(SECKILL_ORDER_QUEUE, true, false, false, args);
    }

    /**
     * Bind the seckill order queue to the exchange with routing key.
     */
    @Bean
    public Binding seckillOrderBinding(Queue seckillOrderQueue, DirectExchange seckillOrderExchange) {
        return BindingBuilder
                .bind(seckillOrderQueue)
                .to(seckillOrderExchange)
                .with(SECKILL_ORDER_ROUTING_KEY);
    }

    /**
     * Dead-letter exchange for seckill orders.
     */
    @Bean
    public DirectExchange seckillOrderDeadLetterExchange() {
        return new DirectExchange(SECKILL_ORDER_DLX, true, false);
    }

    /**
     * Dead-letter queue for seckill orders (manual inspection / compensation).
     */
    @Bean
    public Queue seckillOrderDeadLetterQueue() {
        return new Queue(SECKILL_ORDER_DLQ, true);
    }

    /**
     * Bind the dead-letter queue to the dead-letter exchange.
     */
    @Bean
    public Binding seckillOrderDeadLetterBinding(Queue seckillOrderDeadLetterQueue, DirectExchange seckillOrderDeadLetterExchange) {
        return BindingBuilder
                .bind(seckillOrderDeadLetterQueue)
                .to(seckillOrderDeadLetterExchange)
                .with(SECKILL_ORDER_DLQ);
    }
}
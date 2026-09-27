package com.hmdp.config;

import com.hmdp.utils.CacheInvalidateListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 消息监听容器配置
 * <p>
 * 用于监听缓存失效广播消息。
 * </p>
 */
@Configuration
public class RedisMessageConfig {

    /**
     * 缓存失效广播频道
     */
    public static final String CACHE_INVALIDATE_CHANNEL = "cache:invalidate";

    @Bean
    public RedisMessageListenerContainer redisContainer(
            RedisConnectionFactory factory,
            CacheInvalidateListener listener) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(listener, new ChannelTopic(CACHE_INVALIDATE_CHANNEL));
        return container;
    }
}

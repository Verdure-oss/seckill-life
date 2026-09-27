package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Redis 缓存失效广播监听器
 * <p>
 * 当其他实例删除本地缓存后，此监听器接收失效消息并同步失效本地缓存，
 * 保障多实例间缓存一致性。
 * </p>
 */
@Slf4j
@Component
public class CacheInvalidateListener implements MessageListener {

    private final LocalCache<String, String> localCache;

    public CacheInvalidateListener(LocalCache<String, String> localCache) {
        this.localCache = localCache;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String key = new String(message.getBody());
        log.debug("收到缓存失效广播: {}", key);
        localCache.evict(key);
    }
}

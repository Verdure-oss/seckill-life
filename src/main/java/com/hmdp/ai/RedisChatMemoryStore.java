package com.hmdp.ai;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.List;

/**
 * 基于Redis的聊天记忆存储
 */
@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String CACHE_KEY_PREFIX = "ai:chat:memory:";

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = CACHE_KEY_PREFIX + memoryId;
        String messagesJson = stringRedisTemplate.opsForValue().get(key);
        if (messagesJson == null || messagesJson.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return ChatMessageDeserializer.messagesFromJson(messagesJson);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = CACHE_KEY_PREFIX + memoryId;
        String messagesJson = ChatMessageSerializer.messagesToJson(messages);
        stringRedisTemplate.opsForValue().set(key, messagesJson, Duration.ofHours(24));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String key = CACHE_KEY_PREFIX + memoryId;
        stringRedisTemplate.delete(key);
    }
}
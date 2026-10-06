package com.hmdp.ai;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * AI 聊天服务
 * <p>
 * 基于 LangChain4j AiServices 装配的对话助手：支持工具调用（查店铺/预约），
 * 会话记忆按用户隔离并持久化到 Redis。
 * </p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ai.openai.enabled", havingValue = "true")
public class AIChatService {

    private final ChatAssistant chatAssistant;

    public AIChatService(ChatAssistant chatAssistant) {
        this.chatAssistant = chatAssistant;
    }

    /**
     * 发送消息到 AI 并获取回复
     * @param message 用户消息
     * @return AI 回复
     */
    public String chat(String message) {
        UserDTO user = UserHolder.getUser();
        String memoryId = user != null ? "user:" + user.getId() : "anonymous";
        return chatAssistant.chat(memoryId, message);
    }
}
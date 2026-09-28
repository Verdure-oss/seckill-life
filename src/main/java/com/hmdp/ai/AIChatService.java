package com.hmdp.ai;

import com.hmdp.utils.UserHolder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * AI 聊天服务
 * <p>
 * 集成 OpenAI 模型 + 工具调用，实现智能对话功能
 * 会话记忆存储在 Redis 中
 * </p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ai.openai.enabled", havingValue = "true")
public class AIChatService {

    @Resource
    private OpenAiChatModel chatModel;

    @Resource
    private RedisChatMemoryStore chatMemoryStore;

    /**
     * 发送消息到 AI 并获取回复
     * @param message 用户消息
     * @return AI 回复
     */
    public String chat(String message) {
        Long userId = UserHolder.getUser().getId();
        String memoryId = userId != null ? "user:" + userId : "anonymous";

        // 获取历史消息
        List<ChatMessage> messages = chatMemoryStore.getMessages(memoryId);
        messages.add(UserMessage.from(message));

        // 执行对话
        ChatResponse response = chatModel.chat(messages);

        AiMessage aiMessage = response.aiMessage();

        // 保存到记忆
        messages.add(aiMessage);
        if (messages.size() > 20) {
            messages = messages.subList(messages.size() - 20, messages.size());
        }
        chatMemoryStore.updateMessages(memoryId, messages);

        return aiMessage.text();
    }
}
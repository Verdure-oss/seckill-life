package com.hmdp.config;

import com.hmdp.ai.ChatAssistant;
import com.hmdp.ai.RedisChatMemoryStore;
import com.hmdp.ai.ReservationTool;
import com.hmdp.ai.ShopQueryTool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/**
 * LangChain4j configuration for AI chat with OpenAI.
 * Only enabled when ai.openai.enabled=true to avoid context startup failures in CI.
 */
@Configuration
@ConditionalOnProperty(name = "ai.openai.enabled", havingValue = "true", matchIfMissing = false)
public class LangChain4jConfig {

    @Value("${ai.openai.api-key:}")
    private String apiKey;

    @Value("${ai.openai.model:gpt-3.5-turbo}")
    private String model;

    @Value("${ai.openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    /**
     * Create OpenAI chat model from environment variables.
     * API key is read from OPENAI_API_KEY environment variable.
     */
    @Bean
    public OpenAiChatModel openAiChatModel() {
        String resolvedApiKey = System.getenv("OPENAI_API_KEY");
        if (resolvedApiKey == null || resolvedApiKey.isEmpty()) {
            resolvedApiKey = this.apiKey;
        }

        return OpenAiChatModel.builder()
                .apiKey(resolvedApiKey)
                .modelName(model)
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * 装配 AI 对话助手：注册店铺查询/预约工具，并按用户隔离会话记忆（持久化到 Redis）。
     * <p>
     * 此前工具类仅声明了 @Tool 注解但从未注册到模型，导致对话中无法触发工具调用；
     * 通过 AiServices 统一将 {ShopQueryTool, ReservationTool} 挂载到 ChatAssistant。
     * </p>
     */
    @Bean
    public ChatAssistant chatAssistant(
            OpenAiChatModel chatModel,
            RedisChatMemoryStore chatMemoryStore,
            ShopQueryTool shopQueryTool,
            ReservationTool reservationTool) {
        return AiServices.builder(ChatAssistant.class)
                .chatModel(chatModel)
                // 每个用户独立记忆窗口，上限 20 条，持久化到 Redis
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .chatMemoryStore(chatMemoryStore)
                        .build())
                .tools(shopQueryTool, reservationTool)
                .build();
    }
}
package com.hmdp.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
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
}

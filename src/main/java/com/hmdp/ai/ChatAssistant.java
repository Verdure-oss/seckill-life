package com.hmdp.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

/**
 * AI 助手服务接口（LangChain4j AiServices 装配入口）。
 * <p>
 * 通过 {@code @MemoryId} 按用户隔离会话记忆（持久化到 Redis），
 * 通过 {@link ShopQueryTool} / {@link ReservationTool} 提供店铺查询与预约能力。
 * </p>
 */
public interface ChatAssistant {

    /**
     * 发起一轮对话。
     *
     * @param memoryId    会话记忆 ID（业务上使用 userId，匿名用户为 "anonymous"）
     * @param userMessage 用户输入
     * @return AI 回复文本
     */
    String chat(@MemoryId String memoryId, @UserMessage String userMessage);
}
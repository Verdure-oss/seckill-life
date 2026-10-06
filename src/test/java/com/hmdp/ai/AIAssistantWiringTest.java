package com.hmdp.ai;

import com.hmdp.utils.RedisIdWorker;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 助手接线测试：验证 AiServices 装配后，工具调用与会话记忆真正生效。
 * （改造前工具仅声明 @Tool 注解、从未注册到模型，本测试用于防止回归）
 */
@ExtendWith(MockitoExtension.class)
class AIAssistantWiringTest {

    @Mock
    private ChatModel chatModel;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RedisIdWorker redisIdWorker;

    @InjectMocks
    private ReservationTool reservationTool;

    private ChatAssistant buildAssistant(Object... tools) {
        return AiServices.builder(ChatAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(id -> MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(20)
                        .chatMemoryStore(new InMemoryChatMemoryStore())
                        .build())
                .tools(tools)
                .build();
    }

    /**
     * 模型请求调用 makeReservation 工具时，AiServices 应真实执行工具，
     * 并把工具结果回传后继续对话得到最终回复。
     */
    @Test
    void chat_whenModelRequestsReservationTool_shouldExecuteToolAndReturnFinalReply() {
        // 第一轮：模型返回工具调用请求
        ChatResponse toolCallResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                        .id("call-1")
                        .name("makeReservation")
                        .arguments("{\"shopId\":1,\"reservationTime\":\"2026-10-07 12:00\",\"peopleCount\":2}")
                        .build()))
                .build();
        // 第二轮：工具结果回传后，模型给出最终回复
        ChatResponse finalResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("预约成功，编号 999"))
                .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolCallResponse, finalResponse);
        when(redisIdWorker.nextId("reservation")).thenReturn(999L);

        ChatAssistant assistant = buildAssistant(reservationTool);

        String reply = assistant.chat("user:1", "帮我预约明天 12:00 到店 2 人");

        assertThat(reply).isEqualTo("预约成功，编号 999");
        // 工具真实执行过：写入 Redis Hash、设置过期、生成预约 ID
        verify(redisIdWorker).nextId("reservation");
        verify(stringRedisTemplate).expire("reservation:999", 1, TimeUnit.DAYS);
        // 模型被调用两轮（工具调用轮 + 最终回复轮）
        verify(chatModel, times(2)).chat(any(ChatRequest.class));
    }

    /**
     * 会话记忆：第二次对话应携带历史消息（user/ai/user），证明 chatMemoryProvider 已接线。
     */
    @Test
    void chat_withMemoryProvider_secondTurnCarriesHistory() {
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("好的")).build());

        ChatAssistant assistant = buildAssistant();

        assistant.chat("user:2", "第一条消息");
        assistant.chat("user:2", "第二条消息");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel, times(2)).chat(captor.capture());
        // 第二轮请求至少包含 user/ai/user 三条消息，说明历史被带上
        assertThat(captor.getAllValues().get(1).messages().size()).isGreaterThanOrEqualTo(3);
    }
}
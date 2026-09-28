package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.ai.AIChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * AI 智能客服控制器
 */
@RestController
@RequestMapping("/ai")
@ConditionalOnProperty(name = "ai.openai.enabled", havingValue = "true")
public class AIController {

    @Resource
    private AIChatService aiChatService;

    /**
     * AI 对话接口
     * @param params 包含 message 参数的用户消息
     * @return Result 封装的 AI 回复
     */
    @PostMapping("/chat")
    public Result chat(@RequestBody Map<String, String> params) {
        String message = params.get("message");
        if (message == null || message.trim().isEmpty()) {
            return Result.fail("请输入消息内容");
        }
        String reply = aiChatService.chat(message);
        return Result.ok(reply);
    }
}
package com.nageoffer.ai.ragent.controller;

import com.nageoffer.ai.ragent.service.RAGEnterpriseService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAGStandardController + MCP工具调用 + 记忆系统 + 上下文管理
 */
@RestController
@RequiredArgsConstructor
public class RAGEnterpriseController {

    private final RAGEnterpriseService ragEnterpriseService;

    @GetMapping(value = "/rag/v3/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestParam String question,
                           @RequestParam(required = false) String conversationId,
                           HttpServletResponse response) {
        SseEmitter emitter = new SseEmitter(0L);
        ragEnterpriseService.streamChat(question, conversationId, response, emitter);
        return emitter;
    }
}

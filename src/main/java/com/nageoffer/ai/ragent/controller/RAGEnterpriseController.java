package com.nageoffer.ai.ragent.controller;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.chat.StreamCallback;
import com.nageoffer.ai.ragent.service.RAGService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * RAGStandardController + MCP工具调用 + 记忆系统 + 上下文管理
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RAGEnterpriseController {

    private final RAGService ragEnterpriseService;

    @GetMapping(value = "/rag/v3/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@RequestParam String question,
                             @RequestParam(defaultValue = "3") Integer topK,
                             @RequestParam(required = false) String conversationId,
                             HttpServletResponse response) {
        String actualConversationId = resolveConversationId(conversationId);
        response.setHeader("X-Conversation-Id", actualConversationId);
        SseEmitter emitter = new SseEmitter(0L);
        try {
            ragEnterpriseService.streamAnswer(question, topK, actualConversationId, new StreamCallback() {
                @Override
                public void onContent(String chunk) {
                    try {
                        sendChunked(emitter, chunk);
                    } catch (Exception e) {
                        log.error("SSE 发送失败", e);
                        emitter.completeWithError(e);
                    }
                }

                @Override
                public void onComplete() {
                    try {
                        emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                    } catch (IOException e) {
                        log.error("SSE 发送失败", e);
                        emitter.completeWithError(e);
                    }
                    emitter.complete();
                }

                @Override
                public void onError(Throwable t) {
                    emitter.completeWithError(t);
                }
            });
        } catch (Exception e) {
            emitter.completeWithError(e);
        }

        return emitter;
    }

    private String resolveConversationId(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return IdUtil.getSnowflakeNextIdStr();
        }
        return conversationId.trim();
    }

    private void sendChunked(SseEmitter emitter, String chunk) throws IOException {
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        try {
            int[] codePoints = chunk.codePoints().toArray();
            for (int codePoint : codePoints) {
                String character = new String(new int[]{codePoint}, 0, 1);
                emitter.send(SseEmitter.event().name("message").data(Map.of("delta", character)));
            }
        } catch (Exception e) {
            log.error("UTF-8 字符分割发送失败，回退到原始文本发送", e);
            emitter.send(SseEmitter.event().name("message").data(Map.of("delta", chunk)));
        }
    }
}

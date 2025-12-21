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
import java.io.PrintWriter;

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
                             @RequestParam(required = false) String sessionId,
                             HttpServletResponse response) {
        String actualSessionId = resolveSessionId(sessionId);
        response.setHeader("X-Session-Id", actualSessionId);
        SseEmitter emitter = new SseEmitter(0L);
        try {
            ragEnterpriseService.streamAnswer(question, topK, actualSessionId, new StreamCallback() {
                @Override
                public void onContent(String chunk) {
                    try {
                        // 使用 SSE 格式发送，前端需要用 EventSource 解析
                        emitter.send(chunk);
                    } catch (Exception e) {
                        log.error("SSE 发送失败", e);
                        emitter.completeWithError(e);
                    }
                }

                @Override
                public void onComplete() {
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

    @GetMapping(value = "/rag/v3/stream-text")
    public void streamText(@RequestParam String question,
                           @RequestParam(defaultValue = "3") Integer topK,
                           @RequestParam(required = false) String sessionId,
                           HttpServletResponse response) throws IOException {
        String actualSessionId = resolveSessionId(sessionId);
        response.setHeader("X-Session-Id", actualSessionId);
        // 设置响应头
        response.setContentType("text/plain;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        PrintWriter writer = response.getWriter();

        try {
            ragEnterpriseService.streamAnswer(question, topK, actualSessionId, new StreamCallback() {
                @Override
                public void onContent(String chunk) {
                    writer.write(chunk);
                    writer.flush();
                }

                @Override
                public void onComplete() {
                    writer.close();
                }

                @Override
                public void onError(Throwable t) {
                    log.error("流式输出异常", t);
                    writer.close();
                }
            });
        } catch (Exception e) {
            log.error("流式处理失败", e);
            writer.close();
        }
    }

    private String resolveSessionId(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return IdUtil.getSnowflakeNextIdStr();
        }
        return sessionId.trim();
    }
}

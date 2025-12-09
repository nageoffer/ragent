package com.nageoffer.ai.ragent.controller;

import com.nageoffer.ai.ragent.rag.chat.StreamCallback;
import com.nageoffer.ai.ragent.service.RAGService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * RAGStandardController + MCP工具调用 + 记忆系统 + 上下文管理
 */
@RestController
@RequiredArgsConstructor
public class RAGEnterpriseController {

    private final RAGService ragEnterpriseService;
    private final Executor executor = Executors.newCachedThreadPool();

    @GetMapping(value = "/rag/v3/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@RequestParam String question,
                             @RequestParam(defaultValue = "3") Integer topK) {
        SseEmitter emitter = new SseEmitter(0L);
        executor.execute(() -> {
            try {
                ragEnterpriseService.streamAnswer(question, topK, new StreamCallback() {
                    @Override
                    public void onContent(String chunk) {
                        try {
                            // 使用 SSE 格式发送，前端需要用 EventSource 解析
                            emitter.send(chunk);
                        } catch (Exception e) {
                            e.printStackTrace();
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
        });

        return emitter;
    }

    @GetMapping(value = "/rag/v3/stream-text")
    public void streamText(@RequestParam String question,
                           @RequestParam(defaultValue = "3") Integer topK,
                           HttpServletResponse response) throws IOException {
        // 设置响应头
        response.setContentType("text/plain;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        PrintWriter writer = response.getWriter();

        try {
            ragEnterpriseService.streamAnswer(question, topK, new StreamCallback() {
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
                    t.printStackTrace();
                    writer.close();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            writer.close();
        }
    }
}

package com.nageoffer.ai.ragent.core.controller;

import com.nageoffer.ai.ragent.core.dto.QueryRequest;
import com.nageoffer.ai.ragent.core.service.ConversationService;
import com.nageoffer.ai.ragent.core.service.RagService;
import com.nageoffer.ai.ragent.core.service.llm.StreamCallback;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ragent/rag")
public class RagController {

    private final RagService ragService;
    private final ConversationService conversationService;
    private final Executor executor = Executors.newCachedThreadPool();

    @PostMapping("/query")
    public RagService.RagAnswer query(@RequestBody QueryRequest req) {
        int topK = (req.getTopK() == null || req.getTopK() <= 0) ? 3 : req.getTopK();
        return ragService.answer(req.getQuestion(), topK);
    }

    @GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@RequestParam String question,
                             @RequestParam(defaultValue = "3") Integer topK) {
        SseEmitter emitter = new SseEmitter(0L);
        executor.execute(() -> {
            try {
                ragService.streamAnswer(question, topK, new StreamCallback() {
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

    @GetMapping(value = "/stream-text")
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
            ragService.streamAnswer(question, topK, new StreamCallback() {
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

    // ========== 带记忆功能的接口 ==========

    /**
     * 带会话记忆的流式问答（纯文本格式）
     */
    /*@GetMapping(value = "/chat")
    public void chat(@RequestParam(required = false) String sessionId,
                     @RequestParam String question,
                     @RequestParam(defaultValue = "3") Integer topK,
                     HttpServletResponse response) throws IOException {

        // 如果没有提供 sessionId，生成一个新的
        String actualSessionId = (sessionId != null && !sessionId.isEmpty())
                ? sessionId
                : UUID.randomUUID().toString();

        response.setContentType("text/plain;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        // 返回 sessionId 给客户端
        response.setHeader("X-Session-Id", actualSessionId);

        PrintWriter writer = response.getWriter();

        try {
            ragService.streamAnswerWithMemory(actualSessionId, question, topK,
                    new StreamLLMService.ContentCallback() {
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
    }*/

    /**
     * 获取会话历史
     */
    @GetMapping("/session/{sessionId}/history")
    public Object getHistory(@PathVariable String sessionId) {
        return conversationService.getHistory(sessionId);
    }

    /**
     * 清除会话
     */
    @DeleteMapping("/session/{sessionId}")
    public Object clearSession(@PathVariable String sessionId) {
        conversationService.clearSession(sessionId);
        return new Object() {
            public final String message = "Session cleared";
            public final String session = sessionId;
        };
    }

    /**
     * 获取所有会话 ID
     */
    @GetMapping("/sessions")
    public Object getAllSessions() {
        Set<String> sessionIds = conversationService.getAllSessionIds();
        return new Object() {
            public final Set<String> sessions = sessionIds;
            public final int count = sessionIds.size();
        };
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.service.RAGService;
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
 * RAGQuickController + 意图识别 + Query重写 + Rerank
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RAGStandardController {

    private final RAGService ragStandardService;

    @GetMapping(value = "/rag/v2/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestParam String question,
                           @RequestParam(defaultValue = "3") Integer topK) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            ragStandardService.streamChat(question, topK, new StreamCallback() {
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

    @GetMapping(value = "/rag/v2/stream-text")
    public void chatText(@RequestParam String question,
                         @RequestParam(defaultValue = "3") Integer topK,
                         HttpServletResponse response) throws IOException {
        // 设置响应头
        response.setContentType("text/plain;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        PrintWriter writer = response.getWriter();

        try {
            ragStandardService.streamChat(question, topK, new StreamCallback() {
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
}

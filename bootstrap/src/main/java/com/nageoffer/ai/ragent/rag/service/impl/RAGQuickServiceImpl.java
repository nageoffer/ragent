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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.service.RAGService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service("ragQuickService")
@RequiredArgsConstructor
public class RAGQuickServiceImpl implements RAGService {

    private final RetrieverService retrieverService;
    private final LLMService llmService;
    private final RerankService rerankService;

    @Override
    public void streamChat(String question, int topK, StreamCallback callback) {
        long tStart = System.nanoTime();

        // ==================== 1. search ====================
        long tSearchStart = System.nanoTime();
        int searchTopK = topK * 3;

        List<RetrievedChunk> roughRetrievedChunks = retrieverService.retrieve(question, searchTopK);
        long tSearchEnd = System.nanoTime();
        System.out.println("[Perf] search(question, topK) 耗时: " + ((tSearchEnd - tSearchStart) / 1_000_000.0) + " ms");

        List<RetrievedChunk> retrievedChunks = rerankService.rerank(question, roughRetrievedChunks, topK);

        // ==================== 2. 构建 context ====================
        long tContextStart = System.nanoTime();
        String context = retrievedChunks.stream()
                .map(h -> "- " + h.getText())
                .collect(Collectors.joining("\n"));
        long tContextEnd = System.nanoTime();
        System.out.println("[Perf] context 构建耗时: " + ((tContextEnd - tContextStart) / 1_000_000.0) + " ms");

        // ==================== 3. 拼接 Prompt ====================
        long tPromptStart = System.nanoTime();
        String prompt = """
                你是专业的企业内 RAG 问答助手，请基于文档内容给出更完整、具备解释性的回答。
                
                请遵循以下规则：
                - 回答必须严格基于【文档内容】
                - 不得虚构信息
                - 回答可以适当丰富，但不要过度扩展
                - 建议采用分点说明、简要解释原因或背景
                - 若文档中没有明确内容，请说明“文档未包含相关信息。”
                
                【文档内容】
                %s
                
                【用户问题】
                %s
                """
                .formatted(context, question);
        long tPromptEnd = System.nanoTime();
        System.out.println("[Perf] prompt 拼接耗时: " + ((tPromptEnd - tPromptStart) / 1_000_000.0) + " ms");

        // ==================== 4. 调用流式 LLM ====================
        long tLlmStart = System.nanoTime();
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .thinking(false)
                .temperature(0D)
                .topP(0.7D)
                .build();
        llmService.streamChat(chatRequest, callback);
        long tLlmEnd = System.nanoTime();
        System.out.println("[Perf] llmStreamService.streamChat 调用耗时: " + ((tLlmEnd - tLlmStart) / 1_000_000.0) + " ms");

        // ==================== 5. 全流程总耗时 ====================
        long tEnd = System.nanoTime();
        double total = (tEnd - tStart) / 1_000_000.0;

        System.out.println("================================");
        System.out.println("[Perf Summary - streamChat]");
        System.out.println("  search:          " + ((tSearchEnd - tSearchStart) / 1_000_000.0) + " ms");
        System.out.println("  build context:   " + ((tContextEnd - tContextStart) / 1_000_000.0) + " ms");
        System.out.println("  build prompt:    " + ((tPromptEnd - tPromptStart) / 1_000_000.0) + " ms");
        System.out.println("  llm call:        " + ((tLlmEnd - tLlmStart) / 1_000_000.0) + " ms");
        System.out.println("--------------------------------");
        System.out.println("  TOTAL:           " + total + " ms");
        System.out.println("================================");
    }
}

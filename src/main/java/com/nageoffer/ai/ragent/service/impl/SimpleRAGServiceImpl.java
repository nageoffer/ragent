package com.nageoffer.ai.ragent.service.impl;

import com.nageoffer.ai.ragent.convention.ChatRequest;
import com.nageoffer.ai.ragent.service.rag.retrieve.RetrievedChunk;
import com.nageoffer.ai.ragent.service.rag.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.service.SimpleRAGService;
import com.nageoffer.ai.ragent.service.rag.chat.LLMService;
import com.nageoffer.ai.ragent.service.rag.chat.StreamCallback;
import com.nageoffer.ai.ragent.service.rag.rerank.RerankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SimpleRAGServiceImpl implements SimpleRAGService {

    private final RetrieverService retrieverService;
    private final LLMService llmService;
    private final RerankService rerankService;

    @Override
    public String answer(String question, int topK) {
        List<RetrievedChunk> retrievedChunks = retrieverService.retrieve(question, topK);

        // 如果没有检索到内容，直接 fallback
        if (retrievedChunks == null || retrievedChunks.isEmpty()) {
            return "未检索到与问题相关的文档内容，请尝试换一个问法。";
        }

        // 拼接上下文
        String context = retrievedChunks.stream()
                .map(h -> "- " + h.getText())
                .collect(Collectors.joining("\n"));

        // 标准化 Prompt（保持和流式一致）
        String prompt = """
                你是专业的企业内 RAG 问答助手，请基于文档内容回答用户问题。
                
                规则：
                1. 回答必须严格基于【文档内容】
                2. 不得虚构信息
                3. 回答可以适度丰富（分点说明更佳）
                4. 若文档未包含答案，请明确说明“文档未包含相关信息。”
                
                【文档内容】
                %s
                
                【用户问题】
                %s
                """.formatted(context, question);

        // 调 LLM
        ChatRequest req = ChatRequest.builder()
                .prompt(prompt)
                .thinking(false)
                .temperature(0D)
                .topP(0.7D)
                .build();

        return llmService.chat(req);
    }

    @Override
    public void streamAnswer(String question, int topK, StreamCallback callback) {
        long tStart = System.nanoTime();

        // ==================== 1. search ====================
        long tSearchStart = System.nanoTime();
        int finalTopK = topK;
        int searchTopK = finalTopK * 3;

        List<RetrievedChunk> roughRetrievedChunks = retrieverService.retrieve(question, searchTopK);
        long tSearchEnd = System.nanoTime();
        System.out.println("[Perf] search(question, topK) 耗时: " + ((tSearchEnd - tSearchStart) / 1_000_000.0) + " ms");

        List<RetrievedChunk> retrievedChunks = rerankService.rerank(question, roughRetrievedChunks, finalTopK);

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
                .prompt(prompt)
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
        System.out.println("[Perf Summary - streamAnswer]");
        System.out.println("  search:          " + ((tSearchEnd - tSearchStart) / 1_000_000.0) + " ms");
        System.out.println("  build context:   " + ((tContextEnd - tContextStart) / 1_000_000.0) + " ms");
        System.out.println("  build prompt:    " + ((tPromptEnd - tPromptStart) / 1_000_000.0) + " ms");
        System.out.println("  llm call:        " + ((tLlmEnd - tLlmStart) / 1_000_000.0) + " ms");
        System.out.println("--------------------------------");
        System.out.println("  TOTAL:           " + total + " ms");
        System.out.println("================================");
    }
}

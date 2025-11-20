package com.nageoffer.ai.ragent.core.service;

import com.nageoffer.ai.ragent.core.convention.ChatRequest;
import com.nageoffer.ai.ragent.core.dto.rag.RAGAnswer;
import com.nageoffer.ai.ragent.core.dto.rag.RAGHit;
import com.nageoffer.ai.ragent.core.service.rag.chat.LLMService;
import com.nageoffer.ai.ragent.core.service.rag.chat.StreamCallback;
import com.nageoffer.ai.ragent.core.service.rag.embedding.OllamaEmbeddingService;
import com.nageoffer.ai.ragent.core.service.rag.rerank.RerankService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RAGService {

    private final MilvusClientV2 milvusClient;
    private final OllamaEmbeddingService embeddingService;
    private final LLMService llmService;
    private final RerankService rerankService;

    @Value("${rag.collection-name}")
    private String collectionName;
    @Value("${rag.metric-type}")
    private String metricType;

    public RAGAnswer answer(String question, int topK) {
        List<RAGHit> hits = search(question, topK);

        // 拼接 RAG 上下文
        String context = hits.stream()
                .map(h -> "- " + h.getText())
                .collect(Collectors.joining("\n"));

        String prompt =
                "你是一个专业的知识库问答助手，请根据以下文档回答用户问题。\n\n" +
                        "【检索到的文档片段】:\n" +
                        context +
                        "\n\n【用户问题】:\n" +
                        question +
                        "\n\n请给出简洁、准确的回答：";

        String answer = llmService.chat(prompt);

        return new RAGAnswer(question, hits, answer);
    }

    public void streamAnswer(String question, int topK, StreamCallback callback) {
        long tStart = System.nanoTime();

        // ==================== 1. search ====================
        long tSearchStart = System.nanoTime();
        int finalTopK = topK;
        int searchTopK = finalTopK * 3;

        List<RAGHit> roughHits = search(question, searchTopK);
        long tSearchEnd = System.nanoTime();
        System.out.println("[Perf] search(question, topK) 耗时: " + ((tSearchEnd - tSearchStart) / 1_000_000.0) + " ms");

        List<RAGHit> hits = rerankService.rerank(question, roughHits, finalTopK);

        // ==================== 2. 构建 context ====================
        long tContextStart = System.nanoTime();
        String context = hits.stream()
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

    private List<RAGHit> search(String query, int topK) {
        // ==================== 1. embed ====================
        long tEmbedStart = System.nanoTime();
        List<Float> emb = embeddingService.embed(query);
        long tEmbedEnd = System.nanoTime();
        System.out.println("[Perf] embed 耗时: " + ((tEmbedEnd - tEmbedStart) / 1_000_000.0) + " ms");

        // ==================== 2. float[] 转换 ====================
        float[] arr = new float[emb.size()];
        for (int i = 0; i < emb.size(); i++) arr[i] = emb.get(i);

        // ==================== 3. normalize ====================
        long tNormStart = System.nanoTime();
        float[] norm = normalize(arr);
        long tNormEnd = System.nanoTime();
        System.out.println("[Perf] normalize 耗时: " + ((tNormEnd - tNormStart) / 1_000_000.0) + " ms");

        // ==================== 4. 构造向量请求 ====================
        List<BaseVector> vectors = List.of(new FloatVec(norm));

        Map<String, Object> params = new HashMap<>();
        params.put("metric_type", metricType);
        params.put("ef", 128);

        SearchReq req = SearchReq.builder()
                .collectionName(collectionName)
                .annsField("embedding")
                .data(vectors)
                .topK(topK)
                .searchParams(params)
                .outputFields(List.of("doc_id", "content", "metadata"))
                .build();

        // ==================== 5. Milvus search ====================
        long tMilvusStart = System.nanoTime();
        SearchResp resp = milvusClient.search(req);
        long tMilvusEnd = System.nanoTime();
        System.out.println("[Perf] Milvus search 耗时: " + ((tMilvusEnd - tMilvusStart) / 1_000_000.0) + " ms");

        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
        if (results == null || results.isEmpty()) return List.of();

        // ==================== 6. 打印 doc_id ====================
        System.out.println("====== Milvus Search Hits ======");
        for (SearchResp.SearchResult r : results.get(0)) {
            Object id = r.getEntity().get("doc_id");
            System.out.println("doc_id = " + (id != null ? id : "null"));
        }
        System.out.println("================================\n");

        // ==================== 7. RagHit 映射 ====================
        long tMapStart = System.nanoTime();
        List<RAGHit> list = results.get(0).stream()
                .map(h -> new RAGHit(
                        Objects.toString(h.getEntity().get("doc_id"), ""),
                        Objects.toString(h.getEntity().get("content"), ""),
                        h.getScore()
                ))
                .collect(Collectors.toList());
        long tMapEnd = System.nanoTime();
        System.out.println("[Perf] RagHit mapping 耗时: " + ((tMapEnd - tMapStart) / 1_000_000.0) + " ms\n");

        // ==================== 8. 打印性能总览 ====================
        double total = (tMapEnd - tEmbedStart) / 1_000_000.0;

        System.out.println("================================");
        System.out.println("[Perf Summary - search]");
        System.out.println("  embed:          " + ((tEmbedEnd - tEmbedStart) / 1_000_000.0) + " ms");
        System.out.println("  normalize:      " + ((tNormEnd - tNormStart) / 1_000_000.0) + " ms");
        System.out.println("  milvus search:  " + ((tMilvusEnd - tMilvusStart) / 1_000_000.0) + " ms");
        System.out.println("  mapping:        " + ((tMapEnd - tMapStart) / 1_000_000.0) + " ms");
        System.out.println("--------------------------------");
        System.out.println("  subtotal:       " + total + " ms");
        System.out.println("================================\n");

        return list;
    }

    private static float[] normalize(float[] v) {
        double sum = 0.0;
        for (float x : v) sum += x * x;
        double len = Math.sqrt(sum);
        float[] nv = new float[v.length];
        for (int i = 0; i < v.length; i++) nv[i] = (float) (v[i] / len);
        return nv;
    }
}

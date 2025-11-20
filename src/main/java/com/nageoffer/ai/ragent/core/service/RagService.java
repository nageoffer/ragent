package com.nageoffer.ai.ragent.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagService {

    private final MilvusClientV2 client;
    private final EmbeddingService embeddingService;
    private final LLMService llmService;

    private final String collectionName;
    private final String metricType;

    private final AtomicLong localId = new AtomicLong(1);
    private final StreamLLMService llmStreamService;
    private final ConversationService conversationService;

    public RagService(
            @Qualifier("customMilvusClient") MilvusClientV2 client,
            EmbeddingService embeddingService,
            LLMService llmService,
            StreamLLMService llmStreamService,
            ConversationService conversationService,
            @Value("${rag.collection-name}") String collectionName,
            @Value("${rag.metric-type}") String metricType) {
        this.client = client;
        this.embeddingService = embeddingService;
        this.llmStreamService = llmStreamService;
        this.conversationService = conversationService;
        this.llmService = llmService;
        this.collectionName = collectionName;
        this.metricType = metricType;
    }

    public long indexDocument(String text) {
        long id = localId.getAndIncrement();
        List<Float> embed = embeddingService.embed(text);

        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.add("vector", floatListToJson(embed));
        obj.addProperty("text", text);

        InsertReq req = InsertReq.builder()
                .collectionName(collectionName)
                .data(Collections.singletonList(obj))
                .build();

        client.insert(req);

        return id;
    }

    public List<RagHit> search(String query, int topK) {
        List<Float> emb = embeddingService.embed(query);

        float[] arr = new float[emb.size()];
        for (int i = 0; i < emb.size(); i++) arr[i] = emb.get(i);

        List<BaseVector> vectors = List.of(new FloatVec(arr));

        Map<String, Object> params = new HashMap<>();
        params.put("metric_type", metricType);
        params.put("nprobe", 10);

        SearchReq req = SearchReq.builder()
                .collectionName(collectionName)
                .annsField("vector")
                .data(vectors)
                .topK(topK)
                .searchParams(params)
                .outputFields(List.of("id", "text"))
                .build();

        SearchResp resp = client.search(req);
        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();

        if (results == null || results.isEmpty()) return List.of();

        return results.get(0).stream()
                .map(h -> new RagHit(
                        ((Number) h.getEntity().get("id")).longValue(),
                        Objects.toString(h.getEntity().get("text"), ""),
                        h.getScore()
                ))
                .collect(Collectors.toList());
    }

    public RagAnswer answer(String question, int topK) {
        List<RagHit> hits = search(question, topK);

        // 拼接 RAG 上下文
        String context = hits.stream()
                .map(h -> "- " + h.text())
                .collect(Collectors.joining("\n"));

        String prompt =
                "你是一个专业的知识库问答助手，请根据以下文档回答用户问题。\n\n" +
                        "【检索到的文档片段】:\n" +
                        context +
                        "\n\n【用户问题】:\n" +
                        question +
                        "\n\n请给出简洁、准确的回答：";

        String answer = llmService.chat(prompt);

        return new RagAnswer(question, hits, answer);
    }

    /**
     * 带记忆功能的流式回答
     */
    public void streamAnswerWithMemory(String sessionId, String question, int topK, StreamLLMService.ContentCallback callback) {
        List<RagHit> hits = search(question, topK);

        // 构建 RAG 上下文
        String ragContext = hits.stream()
                .map(h -> "- " + h.text())
                .collect(Collectors.joining("\n"));

        // 使用 ConversationService 构建包含历史的完整上下文
        String prompt = conversationService.buildContextWithHistory(sessionId, ragContext, question);

        log.info("\nprompt {}", prompt);

        // 保存用户问题到会话
        conversationService.addMessage(sessionId, "user", question);

        // 用于累积 AI 的完整回答
        StringBuilder fullAnswer = new StringBuilder();

        // 调用流式 LLM
        llmStreamService.streamChat(prompt, new StreamLLMService.ContentCallback() {
            @Override
            public void onContent(String chunk) {
                fullAnswer.append(chunk);
                callback.onContent(chunk);
            }

            @Override
            public void onComplete() {
                // 保存 AI 回答到会话
                conversationService.addMessage(sessionId, "assistant", fullAnswer.toString());
                callback.onComplete();
            }

            @Override
            public void onError(Throwable t) {
                callback.onError(t);
            }
        });
    }

    /**
     * 原有的无记忆流式回答（保持向后兼容）
     */
    public void streamAnswer(String question, int topK, StreamLLMService.ContentCallback callback) {

        List<RagHit> hits = search(question, topK);

        // 构建 RAG Prompt
        String context = hits.stream()
                .map(h -> "- " + h.text())
                .collect(Collectors.joining("\n"));

        String prompt = "你是专业的企业内 RAG 问答助手。请遵守以下规则：\n\n" +
                "  - 严格基于文档内容\n" +
                "  - 不得长篇大论\n\n" +
                "【文档内容】\n" + context + "\n\n" +
                "【用户问题】\n" + question;

        // 直接走流式大模型
        llmStreamService.streamChat(prompt, callback);
    }

    private JsonArray floatListToJson(List<Float> list) {
        JsonArray arr = new JsonArray();
        list.forEach(arr::add);
        return arr;
    }

    public record RagHit(long id, String text, double score) {
    }

    public record RagAnswer(String question, List<RagHit> hits, String answer) {
    }
}

package com.nageoffer.ai.ragent.service.rag.retrieve;

import java.util.List;

/**
 * 向量检索服务接口（RetrieverService）
 * <p>
 * 用途说明：
 * - 封装对向量数据库（如 Milvus / pgVector / Elasticsearch KNN）的检索能力
 * - 负责从向量库中查找与用户问题（Query）最相关的若干文档片段（Chunk）
 * - 是 RAG 系统中 Retrieval 阶段的核心组件
 * <p>
 * 工作流程：
 * 1. 获取 Query 的 embedding（通常由 EmbeddingService 提供）
 * 2. 在向量库中进行相似度搜索
 * 3. 返回排序后的相关 Chunk（RAGHit）
 * <p>
 * 特点：
 * - 可将检索与大模型（LLM）调用解耦，便于替换搜索实现
 * - 可基于不同召回策略扩展：向量检索、混合检索、符号搜索、多模态检索等
 * <p>
 * 注意事项：
 * - topK 不宜过大，一般 3〜8 为最佳区间
 * - 建议对 vector 维度进行校验，避免与向量库 schema 不匹配
 */
public interface RetrieverService {

    /**
     * 根据自然语言 Query 进行检索
     * <p>
     * 说明：
     * - 内部通常会先调用 EmbeddingService.embed(query) 获取向量
     * - 然后在向量库中执行相似度搜索
     * - 返回命中文档 Chunk 的列表，已按相似度倒序排序
     * <p>
     * 示例：
     * retrieve("请介绍入职流程", 3)
     *
     * @param query 用户自然语言问题
     * @param topK  返回的命中数量
     * @return RetrievedChunk 列表（包含 chunk 内容、得分、metadata 等）
     */
    default List<RetrievedChunk> retrieve(String query, int topK) {
        RetrieveRequest req = RetrieveRequest.builder()
                .query(query)
                .topK(topK)
                .build();
        return retrieve(req);
    }

    /**
     * 根据自然语言 Query 进行检索，支持扩展参数
     * <p>
     * 说明：
     * - 内部通常会先调用 EmbeddingService.embed(query) 获取向量
     * - 然后在向量库中执行相似度搜索
     * - 返回命中文档 Chunk 的列表，已按相似度倒序排序
     * <p>
     *
     * @param retrieveParam 向量检索请求参数
     * @return RetrievedChunk 列表（包含 chunk 内容、得分、metadata 等）
     */
    List<RetrievedChunk> retrieve(RetrieveRequest retrieveParam);

    /**
     * 根据向量直接检索（可选使用）
     * <p>
     * 说明：
     * - 场景适用于：Query embedding 已预先计算的情况
     * - 避免重复调用 embedding 模型
     * - 常用于多轮对话中复用 embedding、批量检索等
     * <p>
     * 注意：
     * - 调用方负责确保 vector 维度与向量库 schema 保持一致
     *
     * @param vector        查询向量（如 float[4096]）
     * @param retrieveParam 向量检索请求参数
     * @return RetrievedChunk 列表（按相似度排序）
     */
    List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest retrieveParam);
}


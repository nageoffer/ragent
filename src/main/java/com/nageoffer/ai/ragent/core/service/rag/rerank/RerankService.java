package com.nageoffer.ai.ragent.core.service.rag.rerank;

import com.nageoffer.ai.ragent.core.dto.rag.RAGHit;

import java.util.List;

/**
 * Rerank 服务：对向量检索出来的一批候选文档进行精排，
 * 按“和 query 的相关度”重新排序，并只返回前 topN 条
 */
public interface RerankService {

    /**
     * @param query      用户问题
     * @param candidates 向量检索出来的一批候选文档（通常是 topK 的 3~5 倍）
     * @param topN       最终希望保留的条数（喂给大模型的 K）
     * @return 经过精排后的前 topN 条文档
     */
    List<RAGHit> rerank(String query, List<RAGHit> candidates, int topN);
}

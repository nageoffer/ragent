package com.nageoffer.ai.ragent.core.service.rag.vector;

import com.nageoffer.ai.ragent.core.service.ChunkService;

import java.util.List;

public interface VectorStoreService {

    /**
     * 将指定文档的所有 chunk 向量入库（实现里请携带 kbId、docId、chunkIndex、text 等元信息）
     */
    void upsert(String kbId, String docId, List<ChunkService.Chunk> chunks, float[][] vectors);

    /**
     * 删除某文档下所有向量（基于 docId 过滤）
     */
    void removeByDocId(String kbId, String docId);

    /**
     * 标注启用/禁用（实现里可以转化为过滤标签）
     */
    void markEnabled(String kbId, String docId, boolean enabled);
}

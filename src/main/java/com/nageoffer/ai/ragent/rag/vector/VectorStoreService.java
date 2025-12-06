package com.nageoffer.ai.ragent.rag.vector;

import com.nageoffer.ai.ragent.rag.chunk.Chunk;

import java.util.List;

public interface VectorStoreService {

    /**
     * 将指定文档的所有 chunk 建立/写入向量索引（实现里请携带 kbId、docId、chunkIndex、text 等元信息）
     */
    void indexDocumentChunks(String kbId, String docId, List<Chunk> chunks, float[][] vectors);

    /**
     * 删除指定文档的所有 chunk 向量索引
     */
    void deleteDocumentVectors(String kbId, String docId);
}

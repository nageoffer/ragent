package com.nageoffer.ai.ragent.core.service.llm;

import java.util.List;

public interface EmbeddingService {

    /**
     * 对单个文本进行向量化
     */
    List<Float> embed(String text);

    /**
     * 对批量文本进行向量化
     */
    List<List<Float>> embedBatch(List<String> texts);

    /**
     * 返回向量维度（如 4096）
     */
    int dimension();
}

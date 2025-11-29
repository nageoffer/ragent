package com.nageoffer.ai.ragent.controller.request;

import lombok.Data;

@Data
public class KnowledgeBaseCreateRequest {

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 嵌入模型，如 qwen3-embedding:8b-fp16
     */
    private String embeddingModel;

    /**
     * Milvus Collection 名称
     */
    private String collectionName;
}

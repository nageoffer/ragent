package com.nageoffer.ai.ragent.controller.request;

import lombok.Data;

@Data
public class KnowledgeDocumentUpdateRequest {

    /**
     * 文档名称
     */
    private String docName;

    /**
     * 是否启用
     */
    private Integer enabled;

    /**
     * 状态：pending / running / failed / success
     */
    private String status;

    /**
     * 分块数（可选：向量化完成后更新）
     */
    private Integer chunkCount;
}

package com.nageoffer.ai.ragent.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DocumentChunk {

    /**
     * 每个 chunk 自己的主键（Milvus doc_id）
     */
    private String chunkId;
    /**
     * 业务文档ID
     */
    private String documentId;
    /**
     * 文本内容
     */
    private String content;
    /**
     * chunk 下标（从0开始）
     */
    private Integer chunkIndex;
    /**
     * 原始 metadata JSON 字符串，方便前端调试
     */
    private String metadataJson;
}

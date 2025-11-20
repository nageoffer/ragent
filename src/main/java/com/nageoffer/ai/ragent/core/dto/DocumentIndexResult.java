package com.nageoffer.ai.ragent.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DocumentIndexResult {

    /**
     * 业务文档ID（用于增删改查）
     */
    private String documentId;
    /**
     * 文件名或标题
     */
    private String name;
    /**
     * 来源类型：pdf/markdown/doc/text/...
     */
    private String sourceType;
    /**
     * 切出来的 chunk 数
     */
    private int chunkCount;
}

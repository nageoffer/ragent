package com.nageoffer.ai.ragent.controller.request;

import lombok.Data;

@Data
public class KnowledgeDocumentCreateRequest {

    /**
     * 所属知识库 ID
     */
    private Long kbId;

    /**
     * 文档名称
     */
    private String docName;

    /**
     * 文件地址
     */
    private String fileUrl;

    /**
     * 文件类型：pdf / markdown / docx 等
     */
    private String fileType;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 是否启用：1-启用，0-禁用（可选，默认 1）
     */
    private Integer enabled;
}

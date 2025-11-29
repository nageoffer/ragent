package com.nageoffer.ai.ragent.controller.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeDocumentVO {
    private Long id;
    private Long kbId;
    private String docName;
    private Boolean enabled;
    private Integer chunkCount;
    private String fileUrl;
    private String fileType;
    private Long fileSize;
    private String status;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

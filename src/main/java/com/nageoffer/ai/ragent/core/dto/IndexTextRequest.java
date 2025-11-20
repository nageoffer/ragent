package com.nageoffer.ai.ragent.core.dto;

import lombok.Data;

@Data
public class IndexTextRequest {
    /**
     * 可选标题，会拼在正文前面
     */
    private String title;
    /**
     * 实际文本内容
     */
    private String content;
    /**
     * 可选：指定业务文档ID，不传则生成新的
     */
    private String documentId;
}
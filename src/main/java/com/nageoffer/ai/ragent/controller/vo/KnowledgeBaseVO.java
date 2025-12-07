package com.nageoffer.ai.ragent.controller.vo;

import lombok.Data;

import java.util.Date;

/**
 * 知识库前端返回对象
 */
@Data
public class KnowledgeBaseVO {

    /**
     * 知识库ID
     */
    private String id;

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 嵌入模型标识
     */
    private String embeddingModel;

    /**
     * Milvus Collection 名称
     */
    private String collectionName;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}

package com.nageoffer.ai.ragent.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 知识库实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_knowledge_base")
public class KnowledgeBaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 嵌入模型标识，如：qwen3-embedding:8b-fp16
     */
    private String embeddingModel;

    /**
     * Milvus Collection 名称（创建后禁止修改）
     */
    private String collectionName;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 修改人
     */
    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 是否删除：0-正常，1-删除
     */
    @TableLogic
    private Integer deleted;
}


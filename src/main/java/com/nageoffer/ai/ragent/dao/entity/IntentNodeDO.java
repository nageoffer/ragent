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

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_intent_node")
public class IntentNodeDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 知识库 ID
     */
    private Long kbId;

    /**
     * 业务唯一标识，如 group-hr / biz-oa-intro
     */
    private String intentCode;

    /**
     * 展示名称
     */
    private String name;

    /**
     * 层级：1=DOMAIN,2=CATEGORY,3=TOPIC
     */
    private Integer level;

    /**
     * 父节点的 intent_code
     */
    private String parentCode;

    /**
     * 描述
     */
    private String description;

    /**
     * 示例问题：JSON 数组字符串
     */
    private String examples;

    /**
     * Milvus Collection 名称（仅对 kind=0 有意义）
     */
    private String collectionName;

    /**
     * MCP 工具 ID（仅对 kind=2 有意义）
     */
    private String mcpToolId;

    /**
     * 类型：0=KB(RAG)，1=SYSTEM，2=MCP
     */
    private Integer kind;

    /**
     * 排序
     */
    private Integer sortOrder;

    /**
     * 短规则片段（可选）
     */
    private String promptSnippet;

    /**
     * 场景用的完整 Prompt 模板（可选）
     */
    private String promptTemplate;

    /**
     * 是否启用
     */
    private Integer enabled;

    private String createBy;
    private String updateBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}

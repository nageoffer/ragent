package com.nageoffer.ai.ragent.core.rag.intention;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class IntentNode {

    public enum Level {
        DOMAIN,      // 顶层：集团信息化 / 业务系统 / 中间件环境信息
        CATEGORY,    // 第二层：人事 / 行政 / OA系统 / Redis ...
        TOPIC        // 第三层：更具体的 Topic，如 系统介绍 / 数据安全 / 架构设计
    }

    /**
     * 唯一标识，如：
     * - "group" / "group-hr" / "biz-oa-intro" / "middleware-redis"
     */
    private String id;

    /**
     * 展示名称，如「人事」「OA系统」「数据安全」
     */
    private String name;

    /**
     * 语义说明，用于向量化时的语义提示词
     */
    private String description;

    /**
     * 所属层级：DOMAIN / CATEGORY / TOPIC
     */
    private Level level;

    /**
     * 父节点 ID，根节点为 null
     */
    private String parentId;

    /**
     * 示例问题：尤其是“叶子节点”，可以放典型问法，帮助向量模型更精准对齐
     */
    @Builder.Default
    private List<String> examples = new ArrayList<>();

    /**
     * 子节点列表，没有子节点 = 叶子
     */
    @Builder.Default
    private List<IntentNode> children = new ArrayList<>();

    /**
     * 预计算好的嵌入向量
     */
    @Builder.Default
    private float[] embedding = null;

    /**
     * 仅用于排查/打印的全路径，如「集团信息化 > 人事」
     */
    @Builder.Default
    private String fullPath = "";

    /**
     * 是否为“最终节点”（叶子节点）：
     * - 叶子节点才挂知识库（Milvus Collection）
     * - 叶子节点才会参与意图匹配打分
     */
    public boolean isLeaf() {
        return children == null || children.isEmpty();
    }

    private String collectionName;
}



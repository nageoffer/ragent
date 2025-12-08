package com.nageoffer.ai.ragent.rag.intent;

import com.nageoffer.ai.ragent.enums.IntentKind;
import com.nageoffer.ai.ragent.enums.IntentLevel;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class IntentNode {

    /**
     * 唯一标识，如：
     * - "group" / "group-hr" / "biz-oa-intro" / "middleware-redis"
     */
    private String id;

    /**
     * 知识库 ID
     */
    private String kbId;

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
    private IntentLevel level;

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
     * 仅向量意图识别测试使用
     */
    @Deprecated
    @Builder.Default
    private float[] embedding = null;

    /**
     * 仅用于排查/打印的全路径，如「集团信息化 > 人事」
     */
    @Builder.Default
    private String fullPath = "";

    /**
     * 这类节点属于知识库还是系统交互
     */
    @Builder.Default
    private IntentKind kind = IntentKind.KB;

    /**
     * Milvus Collection 名称（仅对 kind=KB 有意义）
     */
    private String collectionName;

    /**
     * MCP 工具 ID（仅对 kind=MCP 有意义）
     */
    private String mcpToolId;

    /**
     * 短规则片段（可选）
     */
    private String promptSnippet;

    /**
     * 场景用的完整 Prompt 模板（可选）
     */
    private String promptTemplate;

    /**
     * 是否为“最终节点”（叶子节点）：
     * - 叶子节点才挂知识库（Milvus Collection）
     * - 叶子节点才会参与意图匹配打分
     */
    public boolean isLeaf() {
        return children == null || children.isEmpty();
    }

    /**
     * 是否为 KB 类型节点
     */
    public boolean isKB() {
        return kind == null || kind == IntentKind.KB;
    }

    /**
     * 是否为 MCP 类型节点
     */
    public boolean isMCP() {
        return kind == IntentKind.MCP;
    }

    /**
     * 是否为 SYSTEM 类型节点
     */
    public boolean isSystem() {
        return kind == IntentKind.SYSTEM;
    }
}



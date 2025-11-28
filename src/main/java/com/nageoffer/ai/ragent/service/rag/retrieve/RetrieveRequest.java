package com.nageoffer.ai.ragent.service.rag.retrieve;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 向量检索请求参数：
 * - 支持基础 query + topK
 * - 支持指定 Milvus collectionName
 * - 支持简单的 metadata 等值过滤（扩展用）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetrieveRequest {

    /**
     * 用户自然语言问题 / 查询语句
     */
    private String query;

    /**
     * 返回 TopK，默认 5
     */
    @Builder.Default
    private int topK = 5;

    /**
     * 目标向量集合名称：
     * - 为空时走默认 Collection
     * - 非空时按指定 Collection 检索
     */
    private String collectionName;

    /**
     * 元数据等值过滤条件（扩展项）：
     * - key 为 metadata 字段名
     * - value 为匹配值
     * 实现层可以根据 Map 自动拼接 Milvus Expr（AND 连接）。
     * <p>
     * 例如：
     * {"biz_type": "ATTENDANCE", "env": "TEST"}
     */
    private Map<String, Object> metadataFilters;
}


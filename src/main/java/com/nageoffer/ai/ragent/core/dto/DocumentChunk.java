package com.nageoffer.ai.ragent.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档切片实体类（DocumentChunk）
 * <p>
 * 用途说明：
 * - 在 RAG 系统中，原始文档会根据长度或语义被拆分为多个 Chunk
 * - 每个 Chunk 会独立向量化并存入向量库（如 Milvus / pgVector）
 * - 该实体用于记录单个 Chunk 的内容、顺序、元数据及业务文档关联关系
 * <p>
 * 典型使用场景：
 * - 文档索引构建（Indexing）
 * - 文档检索召回（Retrieval）
 * - 前端查看 Chunk 内容、元数据调试
 * <p>
 * 注意事项：
 * - chunkId 用于 Milvus 向量记录主键，必须保持全局唯一
 * - documentId 用于标识同一份文档，所有 Chunk 共享相同的 documentId
 * - metadataJson 为 JSON 字符串，建议保持轻量但可扩展
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DocumentChunk {

    /**
     * Chunk 在向量库中的唯一标识
     * 说明：
     * - 对应 Milvus 中的主键（doc_id）
     * - 用于查询、更新、删除指定的向量片段
     */
    private String chunkId;

    /**
     * 业务侧文档唯一标识
     * 说明：
     * - 同一份文档会被切分成多个 Chunk
     * - 所有属于该文档的 Chunk 均共享该 ID
     */
    private String documentId;

    /**
     * Chunk 文本内容
     * 说明：
     * - 存储实际参与向量化的内容
     * - 通常为按长度或语义切分后的片段
     */
    private String content;

    /**
     * Chunk 在同一文档中的序号（从 0 开始）
     * 说明：
     * - 用于保持文档原始顺序
     * - 便于前端或 RAG 侧按顺序回溯原文
     */
    private Integer chunkIndex;

    /**
     * 原始元数据（JSON 字符串）
     * 说明：
     * - 用于调试或展示，如：文件名、页码、offset、来源等
     * - 不参与向量化计算
     * - 前端可直接展示无需解析
     */
    private String metadataJson;
}

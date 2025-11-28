package com.nageoffer.ai.ragent.dto;

import lombok.Data;

/**
 * 用户查询请求实体类（QueryRequest）
 * <p>
 * 用途说明：
 * - 用于前端向 RAG 系统发起问答请求时携带查询参数
 * - 包含用户输入的问题以及召回向量的数量（topK）
 * <p>
 * 使用场景：
 * - RAG 检索接口（/query /search /ask）
 * - 前端输入问题后发送到后端进行向量搜索 + 大模型生成回答
 * <p>
 * 注意事项：
 * - topK 若为空则使用默认值（通常为 3）
 * - question 内容需进行必要的清洗，例如：trim、空判断
 */
@Data
public class QueryRequest {

    /**
     * 用户输入的问题（自然语言 Query）
     * 示例：
     * - “请介绍一下入职流程？”
     * - “请问公司年假如何计算？”
     * <p>
     * 用途：
     * - 用于向量化生成查询向量
     * - 并用于最终 LLM 构造 Prompt
     */
    private String question;

    /**
     * 检索召回的向量数量（Top-K）
     * 说明：
     * - 可选参数，若未传入则使用默认值 3
     * - 较大 topK 会带来更多上下文，但也可能引入噪声
     * - 建议一般范围：3 ~ 8
     */
    private Integer topK;
}

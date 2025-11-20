package com.nageoffer.ai.ragent.core.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RAG 问答结果包装对象
 * <p>
 * 用于封装一次检索增强问答的输入问题 命中结果列表 和最终回答内容
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RAGAnswer {

    /**
     * 用户原始问题文本
     */
    private String question;

    /**
     * RAG 检索命中的文档或片段列表
     * 一般按相关度从高到低排序
     */
    private List<RAGHit> hits;

    /**
     * 大模型在结合命中内容后生成的最终回答
     */
    private String answer;
}

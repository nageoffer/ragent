package com.nageoffer.ai.ragent.service.rag.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 每个叶子分类节点对应的 LLM 匹配分数
 */
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class NodeScore {

    /**
     * 意图节点
     */
    private IntentNode node;

    /**
     * 打分结果
     */
    private double score;
}

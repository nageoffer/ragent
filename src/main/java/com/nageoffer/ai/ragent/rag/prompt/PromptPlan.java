package com.nageoffer.ai.ragent.rag.prompt;

import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@RequiredArgsConstructor
@Builder
public class PromptPlan {

    /**
     * 剔除无检索结果后保留的意图
     */
    private List<NodeScore> retainedIntents;

    /**
     * 选用的基模板（单意图且有模板才会有值，否则为 null 表示用默认模板）
     */
    private String baseTemplate;

    /**
     * 合并后的意图片段（单意图走模板时通常为空；多意图则为聚合片段）
     */
    private String intentRules;
}

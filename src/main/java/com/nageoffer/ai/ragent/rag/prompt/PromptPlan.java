package com.nageoffer.ai.ragent.rag.prompt;

import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Data
@RequiredArgsConstructor
@AllArgsConstructor
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
}

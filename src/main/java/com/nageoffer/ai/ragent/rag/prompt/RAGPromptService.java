package com.nageoffer.ai.ragent.rag.prompt;

import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.retrieve.RetrievedChunk;

import java.util.List;
import java.util.Map;

/**
 * 构建 RAG 提示词的服务
 * <p>
 * 约定：
 * 1) 若意图补充规则为空，则不插入对应标题与说明；
 * 2) 返回的 Prompt 会做简单空行清理（最多连续两个换行）
 */
public interface RAGPromptService {

    /**
     * 最简单构建：用默认模板，且无意图片段
     */
    String buildPrompt(String docContent, String userQuestion);

    /**
     * 默认模板 + 可选意图片段
     */
    String buildPrompt(String docContent, String userQuestion, String intentRules);

    /**
     * 可覆盖基模板 + 可选意图片段（baseTemplate 为空则退回默认模板）
     */
    String buildPrompt(String docContent, String userQuestion, String intentRules, String baseTemplate);

    /**
     * 规划提示词的组成（会剔除未命中检索的意图，并据此决定模板与片段策略）
     *
     * @param intents      所有候选意图（含打分）
     * @param intentChunks intent 唯一标识 -> 检索到的 chunk 列表（为空或不存在即视为未命中）
     */
    PromptPlan planPrompt(List<NodeScore> intents, Map<String, List<RetrievedChunk>> intentChunks);

    /**
     * 一站式：基于意图+检索结果直接产出 Prompt（内部先 plan，再 build）
     */
    String buildPrompt(String docContent, String userQuestion,
                       List<NodeScore> intents, Map<String, List<RetrievedChunk>> intentChunks);

}


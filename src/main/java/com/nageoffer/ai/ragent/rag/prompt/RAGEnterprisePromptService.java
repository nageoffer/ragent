package com.nageoffer.ai.ragent.rag.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.retrieve.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.constant.RAGEnterpriseConstant.RAG_ENTERPRISE_PROMPT;

/**
 * 默认实现：使用占位符 {{INTENT_RULES}} 控制可选段落的插入
 */
@Service("ragEnterprisePromptService")
public class RAGEnterprisePromptService implements RAGPromptService {

    /**
     * 允许 2+ 个连续换行被压成 2 个，成品更干净
     */
    @Override
    public String buildPrompt(String docContent, String userQuestion, String intentRules, String baseTemplate) {
        String tpl = StrUtil.isNotBlank(baseTemplate) ? baseTemplate : RAG_ENTERPRISE_PROMPT;

        String withIntent = PromptTemplateUtils.injectIntentRules(tpl, intentRules);
        String prompt = withIntent.formatted(
                defaultString(docContent).trim(),
                defaultString(userQuestion).trim()
        );

        return PromptTemplateUtils.cleanupPrompt(prompt);
    }

    @Override
    public PromptPlan planPrompt(List<NodeScore> intents, Map<String, List<RetrievedChunk>> intentChunks) {
        List<NodeScore> safeIntents = intents == null ? Collections.emptyList() : intents;

        // 1) 先剔除“未命中检索”的意图
        List<NodeScore> retained = safeIntents.stream()
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    String key = nodeKey(node);
                    List<RetrievedChunk> chunks = intentChunks == null ? null : intentChunks.get(key);
                    return CollUtil.isNotEmpty(chunks);
                })
                .toList();

        if (retained.isEmpty()) {
            // 没有任何可用意图：无基模板，无片段（上层可根据业务选择 fallback）
            return new PromptPlan(Collections.emptyList(), null, "");
        }

        // 2) 单 / 多意图的模板与片段策略
        if (retained.size() == 1) {
            IntentNode only = retained.get(0).getNode();
            String tpl = StrUtil.emptyIfNull(only.getPromptTemplate()).trim();

            if (StrUtil.isNotBlank(tpl)) {
                // 单意图 + 有模板：默认只用模板，避免 snippet 与模板规则“撞车”
                // 如果模板作者明确预留了 {{INTENT_RULES}}，才注入 snippet（更可控）
                return new PromptPlan(retained, tpl, "");
            } else {
                // 单意图 + 无模板：走默认模板，snippet 作为补充规则
                String snippet = StrUtil.emptyIfNull(only.getPromptSnippet()).trim();
                String rules = StrUtil.isNotBlank(snippet) ? snippet : "";
                return new PromptPlan(retained, null, rules);
            }
        } else {
            // 多意图：统一默认模板，合并所有片段（去空/去重）
            String mergedRules = retained.stream()
                    .map(ns -> ns.getNode().getPromptSnippet())
                    .filter(StrUtil::isNotBlank)
                    .map(String::trim)
                    .distinct()
                    .collect(Collectors.joining("\n\n"));
            return new PromptPlan(retained, null, mergedRules);
        }
    }

    @Override
    public String buildPrompt(String docContent, String userQuestion,
                              List<NodeScore> intents, Map<String, List<RetrievedChunk>> intentChunks) {
        PromptPlan plan = planPrompt(intents, intentChunks);
        return buildPrompt(docContent, userQuestion, plan.getIntentRules(), plan.getBaseTemplate());
    }

    // === 工具方法 ===

    /**
     * 统一从 IntentNode 取 key（优先 intentCode，退化为 id）
     */
    private static String nodeKey(IntentNode node) {
        if (node == null) return "";
        if (StrUtil.isNotBlank(node.getId())) return node.getId();
        return String.valueOf(node.getId());
    }

    private static String defaultString(String s) {
        return s == null ? "" : s;
    }
}

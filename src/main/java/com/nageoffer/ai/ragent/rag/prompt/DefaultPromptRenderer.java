package com.nageoffer.ai.ragent.rag.prompt;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Service;

import java.util.Map;
import static com.nageoffer.ai.ragent.constant.RAGEnterpriseConstant.MCP_KB_MIXED_PROMPT;
import static com.nageoffer.ai.ragent.constant.RAGEnterpriseConstant.MCP_ONLY_PROMPT;
import static com.nageoffer.ai.ragent.constant.RAGEnterpriseConstant.RAG_ENTERPRISE_PROMPT;

@Service
public class DefaultPromptRenderer implements PromptRenderer {

    @Override
    public String render(PromptBuildPlan plan) {
        if (plan == null || plan.getScene() == null) {
            return "";
        }

        String template = StrUtil.isNotBlank(plan.getBaseTemplate())
                ? plan.getBaseTemplate()
                : defaultTemplate(plan.getScene());

        if (StrUtil.isBlank(template)) {
            return "";
        }

        String withRules = PromptTemplateUtils.injectIntentRules(template, plan.getIntentRules());
        String prompt = formatByScene(withRules, plan.getScene(), plan.getSlots());
        return PromptTemplateUtils.cleanupPrompt(prompt);
    }

    private String defaultTemplate(PromptScene scene) {
        return switch (scene) {
            case KB_ONLY -> RAG_ENTERPRISE_PROMPT;
            case MCP_ONLY -> MCP_ONLY_PROMPT;
            case MIXED -> MCP_KB_MIXED_PROMPT;
            case EMPTY -> "";
        };
    }

    private String formatByScene(String template, PromptScene scene, Map<String, String> slots) {
        String mcp = resolveSlot(slots, PromptSlots.MCP_CONTEXT);
        String kb = resolveSlot(slots, PromptSlots.KB_CONTEXT);
        String question = resolveSlot(slots, PromptSlots.QUESTION);

        return switch (scene) {
            case KB_ONLY -> template.formatted(kb, question);
            case MCP_ONLY -> template.formatted(mcp, question);
            case MIXED -> template.formatted(mcp, kb, question);
            case EMPTY -> template;
        };
    }

    private String resolveSlot(Map<String, String> slots, String key) {
        if (slots == null) {
            return "";
        }
        return StrUtil.emptyIfNull(slots.get(key)).trim();
    }
}

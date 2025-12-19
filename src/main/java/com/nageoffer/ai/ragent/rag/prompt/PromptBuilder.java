package com.nageoffer.ai.ragent.rag.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.constant.RAGEnterpriseConstant.MCP_KB_MIXED_PROMPT;
import static com.nageoffer.ai.ragent.constant.RAGEnterpriseConstant.MCP_ONLY_PROMPT;
import static com.nageoffer.ai.ragent.constant.RAGEnterpriseConstant.RAG_ENTERPRISE_PROMPT;

@Service
public class PromptBuilder {

    private final RAGPromptService ragPromptService;
    private final MCPPromptService mcpPromptService;

    public PromptBuilder(
            MCPPromptService mcpPromptService,
            @Qualifier("ragEnterprisePromptService") RAGPromptService ragPromptService) {
        this.ragPromptService = ragPromptService;
        this.mcpPromptService = mcpPromptService;
    }

    public String buildPrompt(PromptContext context) {
        PromptBuildPlan plan = plan(context);
        return render(plan);
    }

    private PromptBuildPlan plan(PromptContext context) {
        if (context.hasMcp() && !context.hasKb()) {
            return planMcpOnly(context);
        }
        if (!context.hasMcp() && context.hasKb()) {
            return planKbOnly(context);
        }
        if (context.hasMcp() && context.hasKb()) {
            return planMixed(context);
        }
        throw new IllegalStateException("PromptContext requires MCP or KB context.");
    }

    private PromptBuildPlan planKbOnly(PromptContext context) {
        PromptPlan plan = ragPromptService.planPrompt(context.getKbIntents(), context.getIntentChunks());
        Map<String, String> slots = new HashMap<>();
        slots.put(PromptSlots.KB_CONTEXT, context.getKbContext());
        slots.put(PromptSlots.QUESTION, context.getQuestion());
        return PromptBuildPlan.builder()
                .scene(PromptScene.KB_ONLY)
                .baseTemplate(plan.getBaseTemplate())
                .intentRules(plan.getIntentRules())
                .slots(slots)
                .build();
    }

    private PromptBuildPlan planMcpOnly(PromptContext context) {
        List<NodeScore> intents = context.getMcpIntents();
        String baseTemplate = null;
        String intentRules = "";

        if (CollUtil.isNotEmpty(intents) && intents.size() == 1) {
            IntentNode node = intents.get(0).getNode();
            String tpl = StrUtil.emptyIfNull(node.getPromptTemplate()).trim();
            if (StrUtil.isNotBlank(tpl)) {
                baseTemplate = tpl;
            }
        }

        if (StrUtil.isBlank(baseTemplate)) {
            intentRules = mcpPromptService.mergeSnippets(intents);
        }

        Map<String, String> slots = new HashMap<>();
        slots.put(PromptSlots.MCP_CONTEXT, context.getMcpContext());
        slots.put(PromptSlots.QUESTION, context.getQuestion());

        return PromptBuildPlan.builder()
                .scene(PromptScene.MCP_ONLY)
                .baseTemplate(baseTemplate)
                .intentRules(intentRules)
                .slots(slots)
                .build();
    }

    private PromptBuildPlan planMixed(PromptContext context) {
        List<NodeScore> allIntents = new ArrayList<>();
        if (CollUtil.isNotEmpty(context.getMcpIntents())) {
            allIntents.addAll(context.getMcpIntents());
        }
        if (CollUtil.isNotEmpty(context.getKbIntents())) {
            allIntents.addAll(context.getKbIntents());
        }

        String intentRules = mcpPromptService.mergeSnippets(allIntents);

        Map<String, String> slots = new HashMap<>();
        slots.put(PromptSlots.MCP_CONTEXT, context.getMcpContext());
        slots.put(PromptSlots.KB_CONTEXT, context.getKbContext());
        slots.put(PromptSlots.QUESTION, context.getQuestion());

        return PromptBuildPlan.builder()
                .scene(PromptScene.MIXED)
                .intentRules(intentRules)
                .slots(slots)
                .build();
    }

    private String render(PromptBuildPlan plan) {
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
        String mcp = slot(slots, PromptSlots.MCP_CONTEXT);
        String kb = slot(slots, PromptSlots.KB_CONTEXT);
        String question = slot(slots, PromptSlots.QUESTION);

        return switch (scene) {
            case KB_ONLY -> template.formatted(kb, question);
            case MCP_ONLY -> template.formatted(mcp, question);
            case MIXED -> template.formatted(mcp, kb, question);
            case EMPTY -> template;
        };
    }

    private String slot(Map<String, String> slots, String key) {
        if (slots == null) {
            return "";
        }
        return StrUtil.emptyIfNull(slots.get(key)).trim();
    }
}

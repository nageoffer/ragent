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

@Service
public class DefaultPromptPlanner implements PromptPlanner {

    private final RAGPromptService ragPromptService;
    private final MCPPromptService mcpPromptService;

    public DefaultPromptPlanner(
            @Qualifier("ragEnterprisePromptService") RAGPromptService ragPromptService,
            MCPPromptService mcpPromptService) {
        this.ragPromptService = ragPromptService;
        this.mcpPromptService = mcpPromptService;
    }

    @Override
    public PromptBuildPlan plan(PromptContext context) {
        if (context.hasMcp() && !context.hasKb()) {
            return planMcpOnly(context);
        }
        if (!context.hasMcp() && context.hasKb()) {
            return planKbOnly(context);
        }
        if (context.hasMcp() && context.hasKb()) {
            return planMixed(context);
        }

        return PromptBuildPlan.builder().scene(PromptScene.EMPTY).build();
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
}

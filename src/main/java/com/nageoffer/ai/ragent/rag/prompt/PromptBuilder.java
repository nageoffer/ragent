package com.nageoffer.ai.ragent.rag.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.nageoffer.ai.ragent.constant.RAGEnterpriseConstant.MCP_KB_MIXED_PROMPT;
import static com.nageoffer.ai.ragent.constant.RAGEnterpriseConstant.MCP_ONLY_PROMPT;
import static com.nageoffer.ai.ragent.constant.RAGEnterpriseConstant.RAG_ENTERPRISE_PROMPT;

@Service
public class PromptBuilder {

    private final RAGPromptService ragPromptService;

    public PromptBuilder(
            @Qualifier("ragEnterprisePromptService") RAGPromptService ragPromptService) {
        this.ragPromptService = ragPromptService;
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
        PromptSlots slots = new PromptSlots(
                context.getMcpContext(),
                context.getKbContext(),
                context.getQuestion()
        );
        return PromptBuildPlan.builder()
                .scene(PromptScene.KB_ONLY)
                .baseTemplate(plan.getBaseTemplate())
                .slots(slots)
                .build();
    }

    private PromptBuildPlan planMcpOnly(PromptContext context) {
        List<NodeScore> intents = context.getMcpIntents();
        String baseTemplate = null;
        if (CollUtil.isNotEmpty(intents) && intents.size() == 1) {
            IntentNode node = intents.get(0).getNode();
            String tpl = StrUtil.emptyIfNull(node.getPromptTemplate()).trim();
            if (StrUtil.isNotBlank(tpl)) {
                baseTemplate = tpl;
            }
        }

        PromptSlots slots = new PromptSlots(
                context.getMcpContext(),
                context.getKbContext(),
                context.getQuestion()
        );

        return PromptBuildPlan.builder()
                .scene(PromptScene.MCP_ONLY)
                .baseTemplate(baseTemplate)
                .slots(slots)
                .build();
    }

    private PromptBuildPlan planMixed(PromptContext context) {
        PromptSlots slots = new PromptSlots(
                context.getMcpContext(),
                context.getKbContext(),
                context.getQuestion()
        );

        return PromptBuildPlan.builder()
                .scene(PromptScene.MIXED)
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

        String prompt = formatByScene(template, plan.getScene(), plan.getSlots());
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

    private String formatByScene(String template, PromptScene scene, PromptSlots slots) {
        PromptSlots safe = slots == null ? PromptSlots.empty() : slots;
        String mcp = StrUtil.emptyIfNull(safe.mcpContext()).trim();
        String kb = StrUtil.emptyIfNull(safe.kbContext()).trim();
        String question = StrUtil.emptyIfNull(safe.question()).trim();

        return switch (scene) {
            case KB_ONLY -> template.formatted(kb, question);
            case MCP_ONLY -> template.formatted(mcp, question);
            case MIXED -> template.formatted(mcp, kb, question);
            case EMPTY -> template;
        };
    }

}

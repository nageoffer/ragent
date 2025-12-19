package com.nageoffer.ai.ragent.rag.prompt;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.constant.RAGEnterpriseConstant.MCP_KB_MIXED_PROMPT;
import static com.nageoffer.ai.ragent.constant.RAGEnterpriseConstant.MCP_ONLY_PROMPT;

/**
 * MCP 提示词服务默认实现（V3 Enterprise 专用）
 * <p>
 * 处理 MCP 相关场景的提示词构建：
 * - 场景3：单问题命中 MCP，使用 promptTemplate 或默认 MCP 提示词
 * - 场景4：多问题多意图全命中 MCP，使用默认 MCP 提示词 + promptSnippet
 * - 场景5：混合命中 MCP 和 KB，使用 MCP_KB_MIXED_PROMPT + promptSnippet
 */
@Service
public class DefaultMCPPromptService implements MCPPromptService {

    @Override
    public String buildMcpOnlyPrompt(String mcpContext, String userQuestion, List<NodeScore> mcpIntents) {
        List<NodeScore> safeIntents = mcpIntents == null ? Collections.emptyList() : mcpIntents;

        // 单意图 + 有自定义模板：使用自定义模板
        if (safeIntents.size() == 1) {
            IntentNode node = safeIntents.get(0).getNode();
            String tpl = StrUtil.emptyIfNull(node.getPromptTemplate()).trim();
            String snippet = StrUtil.emptyIfNull(node.getPromptSnippet()).trim();

            if (StrUtil.isNotBlank(tpl)) {
                // 单意图 + 有模板：使用自定义模板
                String withIntent = PromptTemplateUtils.injectIntentRules(tpl, snippet);
                return formatPrompt(withIntent, mcpContext, userQuestion);
            }
        }

        // 多意图或无自定义模板：使用默认 MCP 提示词 + 合并 snippets
        String mergedSnippets = mergeSnippets(safeIntents);
        String withIntent = PromptTemplateUtils.injectIntentRules(MCP_ONLY_PROMPT, mergedSnippets);
        return formatPrompt(withIntent, mcpContext, userQuestion);
    }

    @Override
    public String buildMixedPrompt(String mcpContext, String kbContext, String userQuestion, List<NodeScore> allIntents) {
        List<NodeScore> safeIntents = allIntents == null ? Collections.emptyList() : allIntents;

        // 混合场景：合并所有意图的 snippets
        String mergedSnippets = mergeSnippets(safeIntents);
        String withIntent = PromptTemplateUtils.injectIntentRules(MCP_KB_MIXED_PROMPT, mergedSnippets);

        // MCP_KB_MIXED_PROMPT 需要三个参数：mcpContext, kbContext, userQuestion
        String prompt = withIntent.formatted(
                defaultString(mcpContext).trim(),
                defaultString(kbContext).trim(),
                defaultString(userQuestion).trim()
        );

        return PromptTemplateUtils.cleanupPrompt(prompt);
    }

    @Override
    public String mergeSnippets(List<NodeScore> intents) {
        if (intents == null || intents.isEmpty()) {
            return "";
        }

        return intents.stream()
                .map(ns -> ns.getNode().getPromptSnippet())
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 格式化 MCP 提示词（两个参数版本：mcpContext, userQuestion）
     */
    private String formatPrompt(String template, String mcpContext, String userQuestion) {
        String prompt = template.formatted(
                defaultString(mcpContext).trim(),
                defaultString(userQuestion).trim()
        );
        return PromptTemplateUtils.cleanupPrompt(prompt);
    }

    private static String defaultString(String s) {
        return s == null ? "" : s;
    }
}

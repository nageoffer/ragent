package com.nageoffer.ai.ragent.rag.prompt;

import cn.hutool.core.util.StrUtil;

import java.util.regex.Pattern;

import static com.nageoffer.ai.ragent.constant.RAGConstant.INTENT_RULES_SECTION;

public final class PromptTemplateUtils {
    private static final Pattern MULTI_BLANK_LINES = Pattern.compile("(\\n){3,}");

    private PromptTemplateUtils() {
    }

    public static String injectIntentRules(String template, String intentRules) {
        if (template == null) {
            return "";
        }
        if (StrUtil.isBlank(intentRules)) {
            return template.replace("{{INTENT_RULES}}", "");
        }

        String section = INTENT_RULES_SECTION.formatted(intentRules.trim());
        if (template.contains("{{INTENT_RULES}}")) {
            return template.replace("{{INTENT_RULES}}", section);
        }
        return section + "\n\n" + template;
    }

    public static String cleanupPrompt(String prompt) {
        if (prompt == null) {
            return "";
        }
        return MULTI_BLANK_LINES.matcher(prompt).replaceAll("\n\n").trim();
    }
}

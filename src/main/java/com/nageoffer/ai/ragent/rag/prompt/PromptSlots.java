package com.nageoffer.ai.ragent.rag.prompt;

public record PromptSlots(String mcpContext, String kbContext, String question) {
    public static PromptSlots empty() {
        return new PromptSlots("", "", "");
    }
}

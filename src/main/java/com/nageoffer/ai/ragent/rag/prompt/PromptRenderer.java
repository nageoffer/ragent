package com.nageoffer.ai.ragent.rag.prompt;

public interface PromptRenderer {
    String render(PromptBuildPlan plan);
}

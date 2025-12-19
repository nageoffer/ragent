package com.nageoffer.ai.ragent.rag.prompt;

public interface PromptPlanner {
    PromptBuildPlan plan(PromptContext context);
}

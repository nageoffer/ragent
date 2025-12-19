package com.nageoffer.ai.ragent.rag.prompt;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class PromptBuildPlan {

    private PromptScene scene;

    private String baseTemplate;

    private String intentRules;

    private Map<String, String> slots;
}

package com.nageoffer.ai.ragent.rag.prompt;

import lombok.Builder;
import lombok.Data;


@Data
@Builder
public class PromptBuildPlan {

    private PromptScene scene;

    private String baseTemplate;

    private PromptSlots slots;
}

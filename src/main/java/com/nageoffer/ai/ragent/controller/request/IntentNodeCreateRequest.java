package com.nageoffer.ai.ragent.controller.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentNodeCreateRequest {

    private String kbId;
    private String intentCode;
    private String name;
    /**
     * 0=DOMAIN,1=CATEGORY,2=TOPIC
     */
    private Integer level;
    private String parentCode;
    private String description;
    private List<String> examples;
    private String mcpToolId;
    private Integer kind;
    private Integer sortOrder;
    private Integer enabled;

    /**
     * 短规则片段（可选）
     */
    private String promptSnippet;

    /**
     * 场景用的完整 Prompt 模板（可选）
     */
    private String promptTemplate;

    /**
     * 参数提取提示词模板（MCP模式专属）
     */
    private String paramPromptTemplate;
}

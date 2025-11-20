package com.nageoffer.ai.ragent.core.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用大模型请求对象，用于封装一次完整对话所需的上下文与参数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatRequest {

    /**
     * 当前用户输入（一般就是 query）
     */
    private String prompt;

    /**
     * 可选：系统提示词（如“你是一个企业知识库助手”）
     */
    private String systemPrompt;

    /**
     * 对话历史（user/assistant 消息列表）
     */
    private List<ChatMessage> history = new ArrayList<>();

    /**
     * 可选：RAG 召回的上下文内容（你可以在实现层拼到 system 或 user 前面）
     */
    private String context;

    // 模型控制参数
    private Double temperature;   // 0~2，越大越发散
    private Double topP;
    private Integer maxTokens;
    private Boolean thinking;

    /**
     * 可选：是否启用工具调用（占坑，后续扩展）
     */
    private boolean enableTools;
}

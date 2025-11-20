package com.nageoffer.ai.ragent.core.service.llm;

import com.nageoffer.ai.ragent.core.convention.ChatRequest;

/**
 * 通用大语言模型（LLM）访问接口，支持同步与流式两种调用方式
 * <p>
 * 通过不同实现类（Ollama / 阿里云百炼 / OpenAI / DeepSeek 等），屏蔽底层 HTTP 协议与认证细节，为业务提供统一的对话能力
 */
public interface LLMService {

    /**
     * 简单场景：仅传入 prompt，同步返回完整回答
     */
    default String chat(String prompt) {
        ChatRequest req = ChatRequest.builder()
                .prompt(prompt)
                .build();
        return chat(req);
    }

    /**
     * 高级场景：支持系统提示词、对话历史、RAG 上下文、温度等参数
     */
    String chat(ChatRequest request);

    /**
     * 简单场景：仅传入 prompt，流式返回完整回答
     */
    default StreamHandle streamChat(String prompt, StreamCallback callback) {
        ChatRequest req = ChatRequest.builder()
                .prompt(prompt)
                .build();
        return streamChat(req, callback);
    }

    /**
     * 流式调用：按片段回调（例如 SSE、分片 token 输出）
     * 返回一个 handle，可以在外部主动 cancel（中途强制停止）
     */
    StreamHandle streamChat(ChatRequest request, StreamCallback callback);
}

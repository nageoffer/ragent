package com.nageoffer.ai.ragent.rag.chat;

import com.nageoffer.ai.ragent.convention.ChatRequest;

/**
 * 通用大语言模型（LLM）访问接口
 * <p>
 * 用途说明：
 * - 为业务层提供统一的大模型访问能力，屏蔽不同厂商/协议的差异
 * - 支持同步调用（一次性返回完整回答）与流式调用（按 token/片段增量输出）
 * - 可通过不同实现类适配各模型平台，如：
 * - 本地推理（Ollama、LM Studio 等）
 * - 阿里云百炼（DashScope）
 * - DeepSeek / OpenAI / Qwen API
 * - 企业内部推理服务
 * <p>
 * 核心能力：
 * - 标准化 Prompt 构造（system / user / context）
 * - RAG 场景支持（可传入检索到的上下文）
 * - 参数化控制（温度、top_p、max_tokens、stop 等）
 * - 流式 token 输出（配合 StreamCallback）
 * <p>
 * 注意事项：
 * - 默认方法 chat(String) / streamChat(String) 主要用于简单问答
 * - 复杂场景（带上下文、多轮对话、控制生成参数）需要使用 ChatRequest
 * - 流式模式下需正确处理 cancel()，并确保资源释放
 */
public interface LLMService {

    /**
     * 同步调用（简化模式）
     * <p>
     * 说明：
     * - 仅传入 prompt，不包含上下文、系统提示词、生成参数等
     * - 底层会自动构造 ChatRequest 并直接执行
     * - 返回完整回答字符串
     * <p>
     * 常用场景：
     * - 单轮提问
     * - 偶发性工具调用
     *
     * @param prompt 用户问题/提示词
     * @return 模型返回的完整回答
     */
    default String chat(String prompt) {
        ChatRequest req = ChatRequest.builder()
                .prompt(prompt)
                .build();
        return chat(req);
    }

    /**
     * 同步调用（高级模式）
     * <p>
     * 说明：
     * - 支持系统提示词（system），对话历史（messages），
     * RAG 上下文（contextChunks），生成参数（temperature 等）
     * - 适用于需要精细控制的大模型调用
     * <p>
     * 返回：
     * - 一次性完整回答，无流式回调
     *
     * @param request ChatRequest 包含完整配置的请求对象
     * @return 模型返回的完整回答
     */
    String chat(ChatRequest request);

    /**
     * 流式调用（简化模式）
     * <p>
     * 说明：
     * - 仅传入 prompt，不指定上下文或生成参数
     * - 模型回答将通过 StreamCallback.onContent() 分段推送
     * - 返回一个 StreamHandle，可随时 cancel() 终止生成
     *
     * @param prompt   用户输入内容
     * @param callback 流式回调处理器
     * @return StreamHandle 可用于取消推理
     */
    default StreamHandle streamChat(String prompt, StreamCallback callback) {
        ChatRequest req = ChatRequest.builder()
                .prompt(prompt)
                .build();
        return streamChat(req, callback);
    }

    /**
     * 流式调用（高级模式）
     * <p>
     * 说明：
     * - 适用于需要上下文、多轮对话、参数控制的流式推理
     * - 模型输出可能按 token 或按句段推送
     * - 所有增量内容通过 callback.onContent() 回调
     * - 调用结束后必须调用 callback.onComplete()
     * - 出现异常时调用 callback.onError()
     * <p>
     * 返回：
     * - StreamHandle 句柄，用于在外部主动 cancel() 终止推理
     *
     * @param request  ChatRequest 完整配置的请求
     * @param callback 流式回调接口
     * @return StreamHandle 用于控制流式执行
     */
    StreamHandle streamChat(ChatRequest request, StreamCallback callback);
}

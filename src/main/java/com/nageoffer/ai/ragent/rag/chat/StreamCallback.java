package com.nageoffer.ai.ragent.rag.chat;

/**
 * 流式响应回调接口（StreamCallback）
 * <p>
 * 用途说明：
 * - 用于处理大模型（LLM）或 RAG 系统的流式输出
 * - 在模型生成回答过程中，会多次触发 onContent() 推送增量内容
 * - 回答结束时触发 onComplete()，出现异常时触发 onError()
 * <p>
 * 使用场景：
 * - SSE / WebSocket 流式响应
 * - 后端对接本地大模型（如 Ollama、DeepSeek、Qwen）的流式推送
 * - 前端实时渲染消息、流式拼接回答
 * <p>
 * 注意事项：
 * - onContent() 调用次数不定，由模型输出节奏决定
 * - onComplete() 必须保证在正常结束时调用一次
 * - onError() 应当捕获所有异常，避免影响客户端体验
 */
public interface StreamCallback {

    /**
     * 接收一次增量内容（Delta Token 或部分片段）
     * <p>
     * 说明：
     * - 模型推送的每一段内容都会通过该方法回调
     * - 内容可能为字符、单词、句子，取决于模型分片策略
     * <p>
     * 示例：
     * onContent("你好");
     * onContent("，我可以帮你解答问题");
     *
     * @param content 当前推送的增量内容（非完整回答）
     */
    void onContent(String content);

    /**
     * 整个推理流程结束（全部内容推送完毕）
     * <p>
     * 用途：
     * - 通知上层进行 UI 收尾动作，如：停止 loading、滚动到底部、拼接完整回答等
     * - 确保在所有 onContent() 调用完成后触发
     */
    void onComplete();

    /**
     * 流式推送过程中出现异常
     * <p>
     * 常见场景：
     * - 模型内部错误
     * - 网络中断
     * - 超时
     * - 解析异常
     *
     * @param error 异常对象，包含具体错误信息
     */
    void onError(Throwable error);
}

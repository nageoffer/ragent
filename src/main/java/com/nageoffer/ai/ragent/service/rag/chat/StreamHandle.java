package com.nageoffer.ai.ragent.service.rag.chat;

/**
 * 流式控制句柄（StreamHandle）
 * <p>
 * 用途说明：
 * - 用于控制大模型或 RAG 系统的流式输出过程
 * - 调用方可在任意时间主动中止流式推理（如用户点击“停止生成”）
 * - 通常由底层流式执行引擎（LLM Client、SSE、WebSocket）创建并返回
 * <p>
 * 使用场景：
 * - 流式问答中用户点击“停止回答”
 * - 后端超时、业务需要提前终止推理
 * - 配合 StreamCallback 一起使用，确保中断后不再继续 onContent() 回调
 * <p>
 * 注意事项：
 * - cancel() 应保证幂等，即多次调用不会导致异常
 * - 取消后应确保底层模型推理中断并释放资源（线程、连接等）
 */
public interface StreamHandle {

    /**
     * 取消当前流式推理任务
     * <p>
     * 说明：
     * - 调用后应立即尝试中止底层模型生成过程
     * - 常用于用户主动取消（例如 ChatGPT 中的“Stop generating”）
     * - 调用后应该不会再继续产生 onContent() 回调
     */
    void cancel();
}

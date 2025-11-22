package com.nageoffer.ai.ragent.core.service;

import com.nageoffer.ai.ragent.core.service.rag.chat.StreamCallback;

/**
 * RAG（Retrieval-Augmented Generation）问答服务接口
 * <p>
 * 用途说明：
 * - 封装 RAG 的完整流程：向量检索（Retrieval） + 大模型生成（Generation）
 * - 为业务层提供简单直接的问答接口，隐藏检索、排序、拼接 Prompt 等细节
 * - 支持同步返回完整回答与流式返回分段回答两种模式
 * <p>
 * 核心流程（通用 RAG Pipeline）：
 * 1. 对用户问题进行向量化（Query Embedding）
 * 2. 在向量库（如 Milvus）中检索 topK 个最相关的 Chunk
 * 3. 构造 Prompt（含上下文 + 用户问题）
 * 4. 调用 LLM 生成最终回答
 * <p>
 * 注意事项：
 * - topK 过大可能引入噪声，过小可能丢失关键上下文，一般建议 3~8
 * - 流式模式应保证回调按顺序输出 token/片段
 * - Retrieval 与 LLM 逻辑应保持解耦，便于替换向量模型或大模型
 */
public interface SimpleRAGService {

    /**
     * 同步 RAG 调用：返回完整回答
     * <p>
     * 说明：
     * - 内部会执行完整 RAG 流程（向量检索 + 生成）
     * - 一次性返回结构化回答对象 RAGAnswer（含回答、命中 chunk、相关性等）
     * - 适用于对实时展示要求不高的场景
     *
     * @param question 用户问题
     * @param topK     检索返回的 chunk 数量
     * @return RAGAnswer 包含最终回答的返回信息
     */
    String answer(String question, int topK);

    /**
     * 流式 RAG 调用：按片段回调增量内容
     * <p>
     * 流式说明：
     * - 支持实时 token/片段输出（SSE、WebSocket）
     * - 检索流程与同步完全一致，只有生成过程是流式的
     * - 所有增量内容通过 StreamCallback.onContent() 推送
     * - 结束后必须触发 onComplete()
     * - 出现异常则触发 onError()
     * <p>
     * 常用场景：
     * - 在线聊天 / 智能助手 / 企业知识库问答
     * - 类似 ChatGPT 的逐字输出体验
     *
     * @param question 用户问题
     * @param topK     检索返回的 chunk 数量
     * @param callback 用于接收流式增量内容的回调接口
     */
    void streamAnswer(String question, int topK, StreamCallback callback);
}


package com.nageoffer.ai.ragent.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 企业版 RAG 扩展接口
 */
public interface RAGEnterpriseService {

    /**
     * 企业版 SSE 流式调用（封装输出格式与会话信息）
     *
     * @param question       用户问题
     * @param conversationId 会话 ID（可选）
     * @param emitter        SSE 发射器
     */
    void streamChat(String question, String conversationId, SseEmitter emitter);

    /**
     * 停止指定 taskId 的流式会话
     *
     * @param taskId 任务 ID
     */
    void stopTask(String taskId);
}

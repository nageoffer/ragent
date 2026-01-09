package com.nageoffer.ai.ragent.service;

import jakarta.servlet.http.HttpServletResponse;
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
     * @param response       HTTP 响应
     * @param emitter        SSE 发射器
     */
    void streamAnswer(String question, String conversationId, HttpServletResponse response, SseEmitter emitter);
}

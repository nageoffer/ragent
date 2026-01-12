package com.nageoffer.ai.ragent.service;

import com.nageoffer.ai.ragent.controller.request.ConversationCreateRequest;
import com.nageoffer.ai.ragent.controller.request.ConversationUpdateRequest;

/**
 * 会话服务接口
 * 提供会话的创建、重命名和删除功能
 */
public interface ConversationService {

    /**
     * 创建或更新会话
     * 如果 ConversationCreateRequest 里的会话 ID 存在则更新，不存在则创建
     *
     * @param request 创建请求对象
     */
    void createOrUpdate(ConversationCreateRequest request);

    /**
     * 重命名会话
     *
     * @param conversationId 会话 ID
     * @param userId         用户 ID
     * @param request        更新请求对象
     */
    void rename(String conversationId, String userId, ConversationUpdateRequest request);

    /**
     * 删除会话
     *
     * @param conversationId 会话 ID
     * @param userId         用户 ID
     */
    void delete(String conversationId, String userId);
}

package com.nageoffer.ai.ragent.rag.memory;

import com.nageoffer.ai.ragent.convention.ChatMessage;

import java.util.List;

/**
 * 对话记忆存储接口
 * 提供对话历史记录的加载、追加和缓存刷新功能
 */
public interface ConversationMemoryStore {

    /**
     * 加载对话历史记录
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @return 对话历史消息列表
     */
    List<ChatMessage> loadHistory(String conversationId, String userId);

    /**
     * 追加消息到对话历史
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @param message        要追加的消息
     */
    void append(String conversationId, String userId, ChatMessage message);

    /**
     * 刷新对话缓存
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     */
    void refreshCache(String conversationId, String userId);
}

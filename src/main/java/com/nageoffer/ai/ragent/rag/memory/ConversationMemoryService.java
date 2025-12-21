package com.nageoffer.ai.ragent.rag.memory;

import com.nageoffer.ai.ragent.convention.ChatMessage;

import java.util.List;

public interface ConversationMemoryService {

    List<ChatMessage> load(String sessionId, String userId, int maxMessages);

    void append(String sessionId, String userId, ChatMessage message);
}

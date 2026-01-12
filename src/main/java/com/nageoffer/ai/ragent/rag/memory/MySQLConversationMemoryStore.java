package com.nageoffer.ai.ragent.rag.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.config.MemoryProperties;
import com.nageoffer.ai.ragent.controller.request.ConversationCreateRequest;
import com.nageoffer.ai.ragent.convention.ChatMessage;
import com.nageoffer.ai.ragent.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.service.ConversationMessageService;
import com.nageoffer.ai.ragent.service.ConversationService;
import com.nageoffer.ai.ragent.service.bo.ConversationMessageBO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MySQLConversationMemoryStore implements ConversationMemoryStore {

    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;
    private final MemoryProperties memoryProperties;

    public MySQLConversationMemoryStore(ConversationService conversationService,
                                        ConversationMessageService conversationMessageService,
                                        MemoryProperties memoryProperties) {
        this.conversationService = conversationService;
        this.conversationMessageService = conversationMessageService;
        this.memoryProperties = memoryProperties;
    }

    @Override
    public List<ChatMessage> loadHistory(String conversationId, String userId) {
        int maxMessages = resolveMaxHistoryMessages();
        List<ConversationMessageDO> dbMessages = conversationMessageService.listLatestMessages(
                conversationId,
                userId,
                maxMessages
        );
        if (CollUtil.isEmpty(dbMessages)) {
            return List.of();
        }

        dbMessages.sort(Comparator.comparing(ConversationMessageDO::getCreateTime));
        List<ChatMessage> result = dbMessages.stream()
                .map(this::toChatMessage)
                .filter(this::isHistoryMessage)
                .collect(Collectors.toList());

        return normalizeHistory(result);
    }

    @Override
    public void append(String conversationId, String userId, ChatMessage message) {
        ConversationMessageBO conversationMessage = ConversationMessageBO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(message.getRole().name().toLowerCase())
                .content(message.getContent())
                .build();
        conversationMessageService.addMessage(conversationMessage);

        if (message.getRole() == ChatMessage.Role.USER) {
            ConversationCreateRequest conversation = ConversationCreateRequest.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .question(message.getContent())
                    .lastTime(new Date())
                    .build();
            conversationService.createOrUpdate(conversation);
        }
    }

    @Override
    public void refreshCache(String conversationId, String userId) {
        // MySQL 直读模式，无需刷新缓存
    }

    private ChatMessage toChatMessage(ConversationMessageDO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        ChatMessage.Role role = ChatMessage.Role.fromString(record.getRole());
        return new ChatMessage(role, record.getContent());
    }

    private List<ChatMessage> normalizeHistory(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> cleaned = messages.stream()
                .filter(this::isHistoryMessage)
                .toList();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        int start = 0;
        while (start < cleaned.size() && cleaned.get(start).getRole() == ChatMessage.Role.ASSISTANT) {
            start++;
        }
        if (start >= cleaned.size()) {
            return List.of();
        }
        return cleaned.subList(start, cleaned.size());
    }

    private boolean isHistoryMessage(ChatMessage message) {
        return message != null
                && (message.getRole() == ChatMessage.Role.USER || message.getRole() == ChatMessage.Role.ASSISTANT)
                && StrUtil.isNotBlank(message.getContent());
    }

    private int resolveMaxHistoryMessages() {
        int maxTurns = memoryProperties.getMaxTurns();
        return maxTurns * 2;
    }
}

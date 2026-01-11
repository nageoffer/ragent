package com.nageoffer.ai.ragent.rag.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.config.MemoryProperties;
import com.nageoffer.ai.ragent.convention.ChatMessage;
import com.nageoffer.ai.ragent.convention.ChatRequest;
import com.nageoffer.ai.ragent.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.chat.LLMService;
import com.nageoffer.ai.ragent.rag.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.service.ConversationGroupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.constant.RAGConstant.CONVERSATION_TITLE_PROMPT_PATH;

@Slf4j
@Service
public class MySQLConversationMemoryStore implements ConversationMemoryStore {

    private final ConversationGroupService conversationGroupService;
    private final MemoryProperties memoryProperties;
    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;

    public MySQLConversationMemoryStore(ConversationGroupService conversationGroupService,
                                        MemoryProperties memoryProperties,
                                        LLMService llmService,
                                        PromptTemplateLoader promptTemplateLoader) {
        this.conversationGroupService = conversationGroupService;
        this.memoryProperties = memoryProperties;
        this.llmService = llmService;
        this.promptTemplateLoader = promptTemplateLoader;
    }

    @Override
    public List<ChatMessage> loadHistory(String conversationId, String userId) {
        int maxMessages = resolveMaxHistoryMessages();
        List<ConversationMessageDO> dbMessages = conversationGroupService.listLatestUserMessages(
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
        persistToDB(conversationId, userId, message);
    }

    @Override
    public void refreshCache(String conversationId, String userId) {
        // MySQL 直读模式，无需刷新缓存
    }

    private void persistToDB(String conversationId, String userId, ChatMessage message) {
        ConversationMessageDO record = ConversationMessageDO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(message.getRole() == null ? null : message.getRole().name().toLowerCase())
                .content(message.getContent())
                .build();
        conversationGroupService.saveMessage(record);
        upsertConversation(conversationId, userId, message);
    }

    private ChatMessage toChatMessage(ConversationMessageDO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        ChatMessage.Role role = parseRole(record.getRole());
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

    private ChatMessage.Role parseRole(String role) {
        if (StrUtil.isBlank(role)) {
            return ChatMessage.Role.USER;
        }
        String normalized = role.trim().toUpperCase();
        try {
            return ChatMessage.Role.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return ChatMessage.Role.USER;
        }
    }

    private void upsertConversation(String conversationId, String userId, ChatMessage message) {
        ConversationDO existing = conversationGroupService.findConversation(conversationId, userId);
        String content = message.getContent() == null ? "" : message.getContent().trim();
        if (existing == null) {
            String title = null;
            if (message.getRole() == ChatMessage.Role.USER && StrUtil.isNotBlank(content)) {
                title = generateTitleFromQuestion(content);
            }
            ConversationDO record = ConversationDO.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .title(title)
                    .lastTime(new java.util.Date())
                    .build();
            conversationGroupService.upsertConversation(record);
            return;
        }
        existing.setLastTime(new java.util.Date());
        if (StrUtil.isBlank(existing.getTitle()) && message.getRole() == ChatMessage.Role.USER) {
            String title = generateTitleFromQuestion(content);
            if (StrUtil.isNotBlank(title)) {
                existing.setTitle(title);
            }
        }
        conversationGroupService.upsertConversation(existing);
    }

    private String generateTitleFromQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            return "";
        }
        int maxLen = memoryProperties.getTitleMaxLength();
        if (maxLen <= 0) {
            maxLen = 30;
        }
        String prompt = promptTemplateLoader.render(
                CONVERSATION_TITLE_PROMPT_PATH,
                Map.of(
                        "title_max_chars", String.valueOf(maxLen),
                        "question", question.trim()
                )
        );
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(ChatMessage.user(prompt)))
                    .temperature(0.1D)
                    .topP(0.3D)
                    .thinking(false)
                    .build();
            return llmService.chat(request);
        } catch (Exception ex) {
            log.warn("生成会话标题失败", ex);
            return "新的问题会话";
        }
    }

    private int resolveMaxHistoryMessages() {
        return requirePositiveMaxTurns() * 2;
    }

    private int requirePositiveMaxTurns() {
        int maxTurns = memoryProperties.getMaxTurns();
        if (maxTurns <= 0) {
            throw new IllegalArgumentException("rag.memory.max-turns must be > 0");
        }
        return maxTurns;
    }
}

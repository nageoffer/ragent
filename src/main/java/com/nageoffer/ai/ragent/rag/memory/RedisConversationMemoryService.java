package com.nageoffer.ai.ragent.rag.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.nageoffer.ai.ragent.config.MemoryProperties;
import com.nageoffer.ai.ragent.convention.ChatMessage;
import com.nageoffer.ai.ragent.convention.ChatRequest;
import com.nageoffer.ai.ragent.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.chat.LLMService;
import com.nageoffer.ai.ragent.service.ConversationGroupService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.constant.RAGEnterpriseConstant.CONVERSATION_SUMMARY_PROMPT;
import static com.nageoffer.ai.ragent.constant.RAGEnterpriseConstant.CONVERSATION_TITLE_PROMPT;

@Slf4j
@Service
public class RedisConversationMemoryService implements ConversationMemoryService {

    private static final String KEY_PREFIX = "ragent:memory:";
    private static final String SUMMARY_LOCK_PREFIX = "ragent:memory:summary:lock:";
    private static final Duration SUMMARY_LOCK_TTL = Duration.ofMinutes(5);
    private static final String SUMMARY_PREFIX = "对话摘要：";

    private final StringRedisTemplate stringRedisTemplate;
    private final ConversationGroupService conversationGroupService;
    private final MemoryProperties memoryProperties;
    private final LLMService llmService;
    private final Executor memorySummaryExecutor;
    private final RedissonClient redissonClient;
    private final Gson gson = new Gson();

    public RedisConversationMemoryService(
            StringRedisTemplate stringRedisTemplate,
            ConversationGroupService conversationGroupService,
            MemoryProperties memoryProperties,
            LLMService llmService,
            @Qualifier("memorySummaryThreadPoolExecutor") Executor memorySummaryExecutor,
            RedissonClient redissonClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.conversationGroupService = conversationGroupService;
        this.memoryProperties = memoryProperties;
        this.llmService = llmService;
        this.memorySummaryExecutor = memorySummaryExecutor;
        this.redissonClient = redissonClient;
    }

    @Override
    public List<ChatMessage> load(String conversationId, String userId) {
        String key = buildKey(conversationId, userId);
        int maxMessages = resolveMaxHistoryMessages();
        ChatMessage summary = loadLatestSummary(conversationId, userId);
        List<String> raw = stringRedisTemplate.opsForList().range(key, -maxMessages, -1);
        if (raw != null && !raw.isEmpty()) {
            List<ChatMessage> cached = normalizeHistory(filterHistoryMessages(parseMessages(raw)));
            if (!cached.isEmpty()) {
                return attachSummary(summary, cached);
            }
        }

        List<ConversationMessageDO> dbMessages = conversationGroupService.listLatestUserMessages(
                conversationId,
                userId,
                maxMessages
        );
        if (CollUtil.isEmpty(dbMessages)) {
            return attachSummary(summary, List.of());
        }
        dbMessages.sort(Comparator.comparing(ConversationMessageDO::getCreateTime));
        List<ChatMessage> result = dbMessages.stream()
                .map(this::toChatMessage)
                .filter(this::isHistoryMessage)
                .collect(Collectors.toList());
        result = normalizeHistory(result);
        if (CollUtil.isNotEmpty(result)) {
            List<String> payloads = result.stream()
                    .map(gson::toJson)
                    .toList();
            stringRedisTemplate.opsForList().rightPushAll(key, payloads);
            applyExpire(key);
        }
        return attachSummary(summary, result);
    }

    @Override
    public void append(String conversationId, String userId, ChatMessage message) {
        String key = buildKey(conversationId, userId);
        persistToDB(conversationId, userId, message);
        if (isHistoryMessage(message)) {
            String payload = gson.toJson(message);
            stringRedisTemplate.opsForList().rightPush(key, payload);
            trimToMaxSize(key);
            applyExpire(key);
        }
        compressIfNeeded(conversationId, userId, message);
    }

    /**
     * 限制 Redis List 最大长度，防止无界增长
     */
    private void trimToMaxSize(String key) {
        int maxMessages = resolveMaxHistoryMessages();
        if (maxMessages <= 0) {
            return;
        }
        stringRedisTemplate.opsForList().trim(key, -maxMessages, -1);
    }

    private String buildKey(String conversationId, String userId) {
        return KEY_PREFIX + userId + ":" + conversationId.trim();
    }

    private List<ChatMessage> parseMessages(List<String> raw) {
        List<ChatMessage> result = new ArrayList<>();
        for (String item : raw) {
            if (StrUtil.isBlank(item)) {
                continue;
            }
            result.add(gson.fromJson(item, ChatMessage.class));
        }
        return result;
    }

    private void applyExpire(String key) {
        int ttlMinutes = memoryProperties.getTtlMinutes();
        if (ttlMinutes > 0) {
            stringRedisTemplate.expire(key, ttlMinutes, TimeUnit.MINUTES);
        }
    }

    private void persistToDB(String conversationId, String userId, ChatMessage message) {
        ConversationMessageDO record = ConversationMessageDO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(message.getRole() == null ? null : message.getRole().name().toLowerCase())
                .content(message.getContent())
                .isSummary(0)
                .build();
        conversationGroupService.saveMessage(record);
        upsertConversation(conversationId, userId, message);
    }

    private void compressIfNeeded(String conversationId, String userId, ChatMessage message) {
        if (!memoryProperties.isSummaryEnabled()) {
            return;
        }
        if (message.getRole() != ChatMessage.Role.ASSISTANT) {
            return;
        }
        CompletableFuture.runAsync(() -> doCompressIfNeeded(conversationId, userId), memorySummaryExecutor)
                .exceptionally(ex -> {
                    log.error("对话记忆摘要异步任务失败", ex);
                    return null;
                });
    }

    private void doCompressIfNeeded(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId)) {
            return;
        }
        int triggerTurns = memoryProperties.getSummaryTriggerTurns();
        int maxTurns = memoryProperties.getMaxTurns();
        if (triggerTurns <= 0 || maxTurns < 0) {
            return;
        }
        String lockKey = SUMMARY_LOCK_PREFIX + userId + ":" + conversationId.trim();
        RLock lock = redissonClient.getLock(lockKey);
        if (!tryLock(lock)) {
            return;
        }
        try {
            long total = conversationGroupService.countUserMessages(conversationId, userId);
            if (total <= triggerTurns || total <= maxTurns) {
                return;
            }
            ConversationMessageDO latestSummary = loadLatestSummaryRecord(conversationId, userId);
            int summarizeCount = (int) Math.max(0, total - maxTurns);
            if (summarizeCount <= 0) {
                return;
            }
            int fetchLimit = Math.max(1, summarizeCount * 2 + 2);
            List<ConversationMessageDO> toSummarize = conversationGroupService.listEarliestUserMessages(
                    conversationId,
                    userId,
                    fetchLimit
            );
            if (toSummarize == null || toSummarize.isEmpty()) {
                return;
            }
            List<ConversationMessageDO> rollingMessages = filterRollingMessages(
                    capByUserTurns(toSummarize, summarizeCount),
                    latestSummary
            );
            if (rollingMessages.isEmpty()) {
                return;
            }
            if (latestSummary != null && rollingMessages.size() < triggerTurns) {
                return;
            }
            String existingSummary = latestSummary == null ? "" : latestSummary.getContent();
            String summary = summarizeMessages(rollingMessages, existingSummary);
            if (StrUtil.isBlank(summary)) {
                return;
            }
            if (latestSummary == null) {
                ConversationMessageDO first = toSummarize.get(0);
                ConversationMessageDO summaryRecord = ConversationMessageDO.builder()
                        .conversationId(conversationId)
                        .userId(userId)
                        .role(ChatMessage.Role.SYSTEM.name().toLowerCase())
                        .content(summary)
                        .isSummary(1)
                        .createTime(first.getCreateTime())
                        .build();
                conversationGroupService.upsertSummary(summaryRecord);
                refreshCache(conversationId, userId);
            } else {
                latestSummary.setContent(summary);
                conversationGroupService.upsertSummary(latestSummary);
            }
        } finally {
            unlockIfOwner(lock);
        }
    }

    private boolean tryLock(RLock lock) {
        try {
            return lock.tryLock(0, SUMMARY_LOCK_TTL.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void unlockIfOwner(RLock lock) {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private List<ConversationMessageDO> filterRollingMessages(List<ConversationMessageDO> messages,
                                                              ConversationMessageDO latestSummary) {
        if (latestSummary == null) {
            return messages;
        }
        java.util.Date cutoff = latestSummary.getUpdateTime();
        if (cutoff == null) {
            cutoff = latestSummary.getCreateTime();
        }
        if (cutoff == null) {
            return messages;
        }
        java.util.Date finalCutoff = cutoff;
        return messages.stream()
                .filter(item -> item.getCreateTime() != null && item.getCreateTime().after(finalCutoff))
                .toList();
    }

    private String summarizeMessages(List<ConversationMessageDO> messages, String existingSummary) {
        String content = buildSummaryContent(messages, existingSummary);
        if (StrUtil.isBlank(content)) {
            return "";
        }
        String prompt = CONVERSATION_SUMMARY_PROMPT.formatted(content);
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();
        try {
            String result = llmService.chat(request);
            return result == null ? "" : result.trim();
        } catch (Exception e) {
            log.error("对话记忆摘要生成失败, conversationId相关消息数: {}", messages.size(), e);
            return "";
        }
    }

    private String buildSummaryContent(List<ConversationMessageDO> messages, String existingSummary) {
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(existingSummary)) {
            sb.append("已有摘要：")
                    .append(existingSummary.trim())
                    .append("\n");
        }
        for (ConversationMessageDO item : messages) {
            if (item == null || StrUtil.isBlank(item.getContent())) {
                continue;
            }
            if (isHistoryRole(item.getRole())) {
                sb.append(toRoleLabel(item.getRole()))
                        .append(item.getContent().trim())
                        .append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String toRoleLabel(String role) {
        if (StrUtil.isBlank(role)) {
            return "";
        }
        return switch (role.trim().toLowerCase()) {
            case "user" -> "用户：";
            case "assistant" -> "助手：";
            case "system" -> "系统：";
            default -> "";
        };
    }

    private void refreshCache(String conversationId, String userId) {
        String key = buildKey(conversationId, userId);
        stringRedisTemplate.delete(key);
        int maxMessages = resolveMaxHistoryMessages();
        if (maxMessages <= 0) {
            return;
        }
        List<ConversationMessageDO> dbMessages = conversationGroupService.listUserMessagesAsc(
                conversationId,
                userId,
                maxMessages
        );
        if (dbMessages == null || dbMessages.isEmpty()) {
            return;
        }
        List<String> payloads = dbMessages.stream()
                .map(this::toChatMessage)
                .filter(this::isHistoryMessage)
                .map(gson::toJson)
                .toList();
        if (!payloads.isEmpty()) {
            stringRedisTemplate.opsForList().rightPushAll(key, payloads);
            applyExpire(key);
        }
    }

    private List<ChatMessage> attachSummary(ChatMessage summary, List<ChatMessage> messages) {
        if (summary == null) {
            return messages;
        }
        List<ChatMessage> result = new ArrayList<>();
        result.add(decorateSummary(summary));
        result.addAll(messages);
        return result;
    }

    private ChatMessage loadLatestSummary(String conversationId, String userId) {
        ConversationMessageDO summary = loadLatestSummaryRecord(conversationId, userId);
        return toChatMessage(summary);
    }

    private ConversationMessageDO loadLatestSummaryRecord(String conversationId, String userId) {
        return conversationGroupService.findLatestSummary(conversationId, userId);
    }

    private ChatMessage toChatMessage(ConversationMessageDO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        ChatMessage.Role role = parseRole(record.getRole());
        return new ChatMessage(role, record.getContent());
    }

    private List<ChatMessage> filterHistoryMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .filter(this::isHistoryMessage)
                .collect(Collectors.toList());
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
        String prompt = CONVERSATION_TITLE_PROMPT.formatted(maxLen, question.trim());
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
        int maxTurns = memoryProperties.getMaxTurns();
        if (maxTurns <= 0) {
            return maxTurns;
        }
        return maxTurns * 2;
    }

    private boolean isHistoryRole(String role) {
        if (StrUtil.isBlank(role)) {
            return false;
        }
        String normalized = role.trim().toLowerCase();
        return "user".equals(normalized) || "assistant".equals(normalized);
    }

    private List<ConversationMessageDO> capByUserTurns(List<ConversationMessageDO> messages, int turns) {
        if (messages == null || messages.isEmpty() || turns <= 0) {
            return List.of();
        }
        List<ConversationMessageDO> result = new ArrayList<>();
        int userCount = 0;
        boolean waitingForAssistant = false;
        for (ConversationMessageDO message : messages) {
            if (message == null || StrUtil.isBlank(message.getContent())) {
                continue;
            }
            if (isHistoryRole(message.getRole())) {
                result.add(message);
                if ("user".equalsIgnoreCase(message.getRole())) {
                    userCount++;
                    waitingForAssistant = true;
                } else if ("assistant".equalsIgnoreCase(message.getRole()) && waitingForAssistant) {
                    waitingForAssistant = false;
                }
                if (userCount >= turns && !waitingForAssistant) {
                    break;
                }
            }
        }
        return result;
    }

    private ChatMessage decorateSummary(ChatMessage summary) {
        if (summary == null || StrUtil.isBlank(summary.getContent())) {
            return summary;
        }
        String content = summary.getContent().trim();
        if (content.startsWith(SUMMARY_PREFIX) || content.startsWith("摘要：")) {
            return summary;
        }
        return ChatMessage.system(SUMMARY_PREFIX + content);
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
}

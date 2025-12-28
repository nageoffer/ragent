package com.nageoffer.ai.ragent.rag.memory;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import com.nageoffer.ai.ragent.config.MemoryProperties;
import com.nageoffer.ai.ragent.convention.ChatMessage;
import com.nageoffer.ai.ragent.convention.ChatRequest;
import com.nageoffer.ai.ragent.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.chat.LLMService;
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

@Slf4j
@Service
public class RedisConversationMemoryService implements ConversationMemoryService {

    private static final String KEY_PREFIX = "ragent:memory:";
    private static final String SUMMARY_LOCK_PREFIX = "ragent:memory:summary:lock:";
    private static final Duration SUMMARY_LOCK_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate stringRedisTemplate;
    private final ConversationMessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final MemoryProperties memoryProperties;
    private final LLMService llmService;
    private final Executor memorySummaryExecutor;
    private final RedissonClient redissonClient;
    private final Gson gson = new Gson();

    public RedisConversationMemoryService(
            StringRedisTemplate stringRedisTemplate,
            ConversationMessageMapper messageMapper,
            ConversationMapper conversationMapper,
            MemoryProperties memoryProperties,
            LLMService llmService,
            @Qualifier("memorySummaryThreadPoolExecutor") Executor memorySummaryExecutor,
            RedissonClient redissonClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.memoryProperties = memoryProperties;
        this.llmService = llmService;
        this.memorySummaryExecutor = memorySummaryExecutor;
        this.redissonClient = redissonClient;
    }

    @Override
    public List<ChatMessage> load(String conversationId, String userId, int maxMessages) {
        String key = buildKey(conversationId, userId);
        if (key == null) {
            return List.of();
        }
        long size = Math.max(maxMessages, 0);
        if (size == 0) {
            return List.of();
        }
        ChatMessage summary = loadLatestSummary(conversationId, userId);
        List<String> raw = stringRedisTemplate.opsForList().range(key, -size, -1);
        if (raw != null && !raw.isEmpty()) {
            List<ChatMessage> cached = parseMessages(raw);
            if (!cached.isEmpty()) {
                return attachSummary(summary, cached);
            }
        }

        List<ConversationMessageDO> dbMessages = messageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, normalizeUserId(userId))
                        .eq(ConversationMessageDO::getIsSummary, 0)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderByDesc(ConversationMessageDO::getCreateTime)
                        .last("limit " + size)
        );
        if (dbMessages == null || dbMessages.isEmpty()) {
            return attachSummary(summary, List.of());
        }
        dbMessages.sort(Comparator.comparing(ConversationMessageDO::getCreateTime));
        List<ChatMessage> result = dbMessages.stream()
                .map(this::toChatMessage)
                .filter(item -> item != null && StrUtil.isNotBlank(item.getContent()))
                .collect(Collectors.toList());
        if (!result.isEmpty()) {
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
        if (key == null || message == null) {
            return;
        }
        persistToDb(conversationId, userId, message);
        String payload = gson.toJson(message);
        stringRedisTemplate.opsForList().rightPush(key, payload);
        trimToMaxSize(key);
        applyExpire(key);
        compressIfNeeded(conversationId, userId, message);
    }

    /**
     * 限制 Redis List 最大长度，防止无界增长
     */
    private void trimToMaxSize(String key) {
        int maxTurns = memoryProperties.getMaxTurns();
        if (maxTurns <= 0) {
            return;
        }
        // 一轮对话包含 user + assistant 两条消息
        int maxSize = maxTurns * 2;
        // LTRIM 保留最新的 maxSize 条消息（从 -maxSize 到 -1）
        stringRedisTemplate.opsForList().trim(key, -maxSize, -1);
    }

    private String buildKey(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId)) {
            return null;
        }
        String safeUserId = normalizeUserId(userId);
        return KEY_PREFIX + safeUserId + ":" + conversationId.trim();
    }

    private String normalizeUserId(String userId) {
        return StrUtil.isBlank(userId) ? "anon" : userId.trim();
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
            stringRedisTemplate.expire(key, Duration.ofMinutes(ttlMinutes));
        }
    }

    private void persistToDb(String conversationId, String userId, ChatMessage message) {
        ConversationMessageDO record = ConversationMessageDO.builder()
                .conversationId(conversationId)
                .userId(normalizeUserId(userId))
                .role(message.getRole() == null ? null : message.getRole().name().toLowerCase())
                .content(message.getContent())
                .isSummary(0)
                .build();
        messageMapper.insert(record);
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
        String lockKey = buildSummaryLockKey(conversationId, userId);
        RLock lock = redissonClient.getLock(lockKey);
        if (!tryLock(lock)) {
            return;
        }
        try {
            int triggerMessages = triggerTurns * 2;
            int keepMessages = maxTurns * 2;
            long total = messageMapper.selectCount(
                    Wrappers.lambdaQuery(ConversationMessageDO.class)
                            .eq(ConversationMessageDO::getConversationId, conversationId)
                            .eq(ConversationMessageDO::getUserId, normalizeUserId(userId))
                            .eq(ConversationMessageDO::getIsSummary, 0)
                            .eq(ConversationMessageDO::getDeleted, 0)
            );
            if (total <= triggerMessages || total <= keepMessages) {
                return;
            }
            ConversationMessageDO latestSummary = loadLatestSummaryRecord(conversationId, userId);
            int summarizeCount = (int) Math.max(0, total - keepMessages);
            if (summarizeCount <= 0) {
                return;
            }
            List<ConversationMessageDO> toSummarize = messageMapper.selectList(
                    Wrappers.lambdaQuery(ConversationMessageDO.class)
                            .eq(ConversationMessageDO::getConversationId, conversationId)
                            .eq(ConversationMessageDO::getUserId, normalizeUserId(userId))
                            .eq(ConversationMessageDO::getIsSummary, 0)
                            .eq(ConversationMessageDO::getDeleted, 0)
                            .orderByAsc(ConversationMessageDO::getCreateTime)
                            .last("limit " + summarizeCount)
            );
            if (toSummarize == null || toSummarize.isEmpty()) {
                return;
            }
            List<ConversationMessageDO> rollingMessages = filterRollingMessages(toSummarize, latestSummary);
            if (rollingMessages.isEmpty()) {
                return;
            }
            if (latestSummary != null && rollingMessages.size() < triggerMessages) {
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
                        .userId(normalizeUserId(userId))
                        .role(ChatMessage.Role.SYSTEM.name().toLowerCase())
                        .content(summary)
                        .isSummary(1)
                        .createTime(first.getCreateTime())
                        .build();
                messageMapper.insert(summaryRecord);
                refreshCache(conversationId, userId);
            } else {
                latestSummary.setContent(summary);
                messageMapper.updateById(latestSummary);
            }
        } finally {
            unlockIfOwner(lock);
        }
    }

    private String buildSummaryLockKey(String conversationId, String userId) {
        String safeUserId = normalizeUserId(userId);
        return SUMMARY_LOCK_PREFIX + safeUserId + ":" + conversationId.trim();
    }

    private boolean tryLock(RLock lock) {
        try {
            return lock.tryLock(0, SUMMARY_LOCK_TTL.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
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
                .prompt(prompt)
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
            if (!"user".equalsIgnoreCase(item.getRole())) {
                continue;
            }
            sb.append(toRoleLabel(item.getRole()))
                    .append(item.getContent().trim())
                    .append("\n");
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
        if (key == null) {
            return;
        }
        stringRedisTemplate.delete(key);
        int maxMessages = memoryProperties.getMaxTurns() * 2;
        if (maxMessages <= 0) {
            return;
        }
        List<ConversationMessageDO> dbMessages = messageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, normalizeUserId(userId))
                        .eq(ConversationMessageDO::getIsSummary, 0)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderByAsc(ConversationMessageDO::getCreateTime)
                        .last("limit " + maxMessages)
        );
        if (dbMessages == null || dbMessages.isEmpty()) {
            return;
        }
        List<String> payloads = dbMessages.stream()
                .map(this::toChatMessage)
                .filter(item -> item != null && StrUtil.isNotBlank(item.getContent()))
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
        result.add(summary);
        result.addAll(messages);
        return result;
    }

    private ChatMessage loadLatestSummary(String conversationId, String userId) {
        ConversationMessageDO summary = loadLatestSummaryRecord(conversationId, userId);
        return toChatMessage(summary);
    }

    private ConversationMessageDO loadLatestSummaryRecord(String conversationId, String userId) {
        List<ConversationMessageDO> summaries = messageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, normalizeUserId(userId))
                        .eq(ConversationMessageDO::getIsSummary, 1)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderByDesc(ConversationMessageDO::getCreateTime)
                        .last("limit 1")
        );
        if (summaries == null || summaries.isEmpty()) {
            return null;
        }
        return summaries.get(0);
    }

    private ChatMessage toChatMessage(ConversationMessageDO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        ChatMessage.Role role = parseRole(record.getRole());
        return new ChatMessage(role, record.getContent());
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
        String safeUserId = normalizeUserId(userId);
        ConversationDO existing = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, safeUserId)
                        .eq(ConversationDO::getDeleted, 0)
        );
        String content = message.getContent() == null ? "" : message.getContent().trim();
        if (existing == null) {
            String title = null;
            if (message.getRole() == ChatMessage.Role.USER && StrUtil.isNotBlank(content)) {
                title = limitLength(content, 30);
            }
            if (StrUtil.isBlank(title)) {
                title = "新会话";
            }
            ConversationDO record = ConversationDO.builder()
                    .conversationId(conversationId)
                    .userId(safeUserId)
                    .title(title)
                    .lastTime(new java.util.Date())
                    .build();
            conversationMapper.insert(record);
            return;
        }
        existing.setLastTime(new java.util.Date());
        if (StrUtil.isBlank(existing.getTitle()) && message.getRole() == ChatMessage.Role.USER) {
            existing.setTitle(limitLength(content, 30));
        }
        conversationMapper.updateById(existing);
    }

    private String limitLength(String text, int maxLen) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen);
    }
}

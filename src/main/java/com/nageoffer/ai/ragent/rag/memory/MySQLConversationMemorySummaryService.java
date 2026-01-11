package com.nageoffer.ai.ragent.rag.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.config.MemoryProperties;
import com.nageoffer.ai.ragent.convention.ChatMessage;
import com.nageoffer.ai.ragent.convention.ChatRequest;
import com.nageoffer.ai.ragent.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.dao.entity.ConversationSummaryDO;
import com.nageoffer.ai.ragent.rag.chat.LLMService;
import com.nageoffer.ai.ragent.rag.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.service.ConversationGroupService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static com.nageoffer.ai.ragent.constant.RAGConstant.CONVERSATION_SUMMARY_PROMPT_PATH;

@Slf4j
@Service
public class MySQLConversationMemorySummaryService implements ConversationMemorySummaryService {

    private static final String SUMMARY_PREFIX = "对话摘要：";
    private static final String SUMMARY_LOCK_PREFIX = "ragent:memory:summary:lock:";
    private static final Duration SUMMARY_LOCK_TTL = Duration.ofMinutes(5);

    private final ConversationGroupService conversationGroupService;
    private final MemoryProperties memoryProperties;
    private final LLMService llmService;
    private final Executor memorySummaryExecutor;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ConversationMemoryStore memoryStore;
    private final RedissonClient redissonClient;

    public MySQLConversationMemorySummaryService(ConversationGroupService conversationGroupService,
                                                 MemoryProperties memoryProperties,
                                                 LLMService llmService,
                                                 @Qualifier("memorySummaryThreadPoolExecutor") Executor memorySummaryExecutor,
                                                 PromptTemplateLoader promptTemplateLoader,
                                                 ConversationMemoryStore memoryStore,
                                                 RedissonClient redissonClient) {
        this.conversationGroupService = conversationGroupService;
        this.memoryProperties = memoryProperties;
        this.llmService = llmService;
        this.memorySummaryExecutor = memorySummaryExecutor;
        this.promptTemplateLoader = promptTemplateLoader;
        this.memoryStore = memoryStore;
        this.redissonClient = redissonClient;
    }

    @Override
    public void compressIfNeeded(String conversationId, String userId, ChatMessage message) {
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

    @Override
    public ChatMessage loadLatestSummary(String conversationId, String userId) {
        ConversationSummaryDO summary = conversationGroupService.findLatestSummary(conversationId, userId);
        return toChatMessage(summary);
    }

    @Override
    public ChatMessage decorateIfNeeded(ChatMessage summary) {
        if (summary == null || StrUtil.isBlank(summary.getContent())) {
            return summary;
        }
        String content = summary.getContent().trim();
        if (content.startsWith(SUMMARY_PREFIX) || content.startsWith("摘要：")) {
            return summary;
        }
        return ChatMessage.system(SUMMARY_PREFIX + content);
    }

    private void doCompressIfNeeded(String conversationId, String userId) {
        long startTime = System.currentTimeMillis();
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return;
        }
        int triggerTurns = memoryProperties.getSummaryTriggerTurns();
        int maxTurns = requirePositiveMaxTurns();
        if (triggerTurns <= 0) {
            return;
        }
        String lockKey = SUMMARY_LOCK_PREFIX + buildLockKey(conversationId, userId);
        RLock lock = redissonClient.getLock(lockKey);
        if (!tryLock(lock)) {
            return;
        }
        try {
            long total = conversationGroupService.countUserMessages(conversationId, userId);
            if (total < triggerTurns) {
                return;
            }
            if (total <= maxTurns) {
                return;
            }
            ConversationSummaryDO latestSummary = conversationGroupService.findLatestSummary(conversationId, userId);
            List<ConversationMessageDO> latestUserTurns = conversationGroupService.listLatestUserOnlyMessages(
                    conversationId,
                    userId,
                    maxTurns
            );
            if (latestUserTurns.isEmpty()) {
                return;
            }
            Date cutoff = resolveCutoff(latestUserTurns);
            if (cutoff == null) {
                return;
            }
            Date after = resolveSummaryStart(latestSummary);
            if (after != null && !after.before(cutoff)) {
                return;
            }
            List<ConversationMessageDO> toSummarize = conversationGroupService.listMessagesBetween(
                    conversationId,
                    userId,
                    after,
                    cutoff
            );
            if (CollUtil.isEmpty(toSummarize)) {
                return;
            }
            Date summaryTime = resolveSummaryTime(toSummarize);
            if (summaryTime == null) {
                return;
            }
            String existingSummary = latestSummary == null ? "" : latestSummary.getContent();
            String summary = summarizeMessages(toSummarize, existingSummary);
            if (StrUtil.isBlank(summary)) {
                return;
            }
            upsertSummary(conversationId, userId, summary, summaryTime);
            log.info("摘要成功 - conversationId: {}, 消息数: {}, 耗时: {}ms",
                    conversationId, toSummarize.size(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("摘要失败 - conversationId: {}, userId: {}", conversationId, userId, e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
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

    private String summarizeMessages(List<ConversationMessageDO> messages, String existingSummary) {
        List<ChatMessage> historys = toHistoryMessages(messages);
        if (CollUtil.isEmpty(historys)) {
            return existingSummary;
        }

        List<ChatMessage> summaryMessages = new ArrayList<>();
        String summaryPrompt = promptTemplateLoader.render(
                CONVERSATION_SUMMARY_PROMPT_PATH,
                Map.of("summary_max_chars", String.valueOf(memoryProperties.getSummaryMaxChars()))
        );
        summaryMessages.add(ChatMessage.system(summaryPrompt));

        if (StrUtil.isNotBlank(existingSummary)) {
            summaryMessages.add(ChatMessage.assistant(
                    "历史摘要（仅用于合并去重，不得作为事实新增来源；若与本轮对话冲突，以本轮对话为准）：\n"
                            + existingSummary.trim()
            ));
        }
        summaryMessages.addAll(historys);
        summaryMessages.add(ChatMessage.user(
                "合并以上对话与历史摘要，去重后输出更新摘要。要求：严格≤{summary_max_chars}字符；仅一行。"
        ));

        ChatRequest request = ChatRequest.builder()
                .messages(summaryMessages)
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();
        try {
            String result = llmService.chat(request);
            String normalized = result.trim();
            log.info("对话摘要生成 - resultChars: {}", normalized.length());
            return normalized;
        } catch (Exception e) {
            log.error("对话记忆摘要生成失败, conversationId相关消息数: {}", messages.size(), e);
            return "";
        }
    }

    private List<ChatMessage> toHistoryMessages(List<ConversationMessageDO> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> history = new ArrayList<>();
        for (ConversationMessageDO item : messages) {
            if (item == null || StrUtil.isBlank(item.getContent())) {
                continue;
            }
            String role = item.getRole();
            if (role == null) {
                continue;
            }
            if ("user".equalsIgnoreCase(role)) {
                history.add(ChatMessage.user(item.getContent().trim()));
                continue;
            }
            if ("assistant".equalsIgnoreCase(role)) {
                history.add(ChatMessage.assistant(item.getContent().trim()));
            }
        }
        return history;
    }

    private ChatMessage toChatMessage(ConversationSummaryDO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        return new ChatMessage(ChatMessage.Role.SYSTEM, record.getContent());
    }

    private Date resolveSummaryStart(ConversationSummaryDO summary) {
        if (summary == null) {
            return null;
        }
        Date after = summary.getUpdateTime();
        return after == null ? summary.getCreateTime() : after;
    }

    private Date resolveCutoff(List<ConversationMessageDO> latestUserTurns) {
        for (int i = latestUserTurns.size() - 1; i >= 0; i--) {
            ConversationMessageDO item = latestUserTurns.get(i);
            if (item != null && item.getCreateTime() != null) {
                return item.getCreateTime();
            }
        }
        return null;
    }

    private Date resolveSummaryTime(List<ConversationMessageDO> toSummarize) {
        return toSummarize.stream()
                .map(ConversationMessageDO::getCreateTime)
                .filter(Objects::nonNull)
                .max(Date::compareTo)
                .orElse(null);
    }

    private void upsertSummary(String conversationId, String userId, String content, Date summaryTime) {
        ConversationSummaryDO summaryRecord = ConversationSummaryDO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .content(content)
                .createTime(summaryTime)
                .updateTime(summaryTime)
                .build();
        conversationGroupService.upsertSummary(summaryRecord);
        memoryStore.refreshCache(conversationId, userId);
    }

    private int requirePositiveMaxTurns() {
        int maxTurns = memoryProperties.getMaxTurns();
        if (maxTurns <= 0) {
            throw new IllegalArgumentException("rag.memory.max-turns must be > 0");
        }
        return maxTurns;
    }

    private String buildLockKey(String conversationId, String userId) {
        return userId.trim() + ":" + conversationId.trim();
    }
}

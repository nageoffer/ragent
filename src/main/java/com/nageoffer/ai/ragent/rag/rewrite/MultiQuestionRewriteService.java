package com.nageoffer.ai.ragent.rag.rewrite;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.constant.RAGConstant;
import com.nageoffer.ai.ragent.constant.RAGEnterpriseConstant;
import com.nageoffer.ai.ragent.convention.ChatMessage;
import com.nageoffer.ai.ragent.convention.ChatRequest;
import com.nageoffer.ai.ragent.rag.chat.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 查询预处理：改写 + 拆分多问句
 */
@Slf4j
@Service("multiQuestionRewriteService")
@RequiredArgsConstructor
public class MultiQuestionRewriteService implements QueryRewriteService {

    private final LLMService llmService;
    private final RAGConfigProperties ragConfigProperties;
    private final QueryTermMappingService queryTermMappingService;

    @Override
    public String rewrite(String userQuestion) {
        return rewriteAndSplit(userQuestion).rewrittenQuestion();
    }

    @Override
    public RewriteResult rewriteWithSplit(String userQuestion) {
        return rewriteAndSplit(userQuestion);
    }

    @Override
    public RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history) {
        return rewriteAndSplit(userQuestion, history);
    }

    /**
     * 先用默认改写做归一化，再进行多问句拆分。
     */
    public RewriteResult rewriteAndSplit(String userQuestion) {
        // 开关关闭：直接做规则归一化 + 规则拆分
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            String normalized = queryTermMappingService.normalize(userQuestion);
            List<String> subs = ruleBasedSplit(normalized);
            return new RewriteResult(normalized, subs);
        }

        String normalizedQuestion = queryTermMappingService.normalize(userQuestion);

        RewriteResult llmResult = callLLMRewriteAndSplit(normalizedQuestion, userQuestion);
        if (llmResult != null) {
            return llmResult;
        }

        // 兜底：使用归一化结果 + 规则拆分
        List<String> subQuestions = ruleBasedSplit(normalizedQuestion);
        return new RewriteResult(normalizedQuestion, subQuestions);
    }

    private RewriteResult rewriteAndSplit(String userQuestion, List<ChatMessage> history) {
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            String normalized = queryTermMappingService.normalize(userQuestion);
            List<String> subs = ruleBasedSplit(normalized);
            return new RewriteResult(normalized, subs);
        }

        String normalizedQuestion = queryTermMappingService.normalize(userQuestion);

        boolean useHistory = shouldUseHistoryForRewrite(normalizedQuestion, history);
        RewriteResult llmResult = useHistory
                ? callLLMRewriteAndSplitWithHistory(normalizedQuestion, userQuestion, history)
                : callLLMRewriteAndSplit(normalizedQuestion, userQuestion);
        if (llmResult != null) {
            return llmResult;
        }

        List<String> subQuestions = ruleBasedSplit(normalizedQuestion);
        return new RewriteResult(normalizedQuestion, subQuestions);
    }

    private RewriteResult callLLMRewriteAndSplit(String normalizedQuestion, String originalQuestion) {
        String prompt = RAGConstant.QUERY_REWRITE_AND_SPLIT_PROMPT.formatted(normalizedQuestion);

        ChatRequest req = ChatRequest.builder()
                .prompt(prompt)
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();

        try {
            String raw = llmService.chat(req);
            RewriteResult parsed = parseRewriteAndSplit(raw);
            if (parsed != null) {
                log.info("""
                        查询改写+拆分：
                        原始问题：{}
                        归一化后：{}
                        改写结果：{}
                        子问题：{}
                        """, originalQuestion, normalizedQuestion, parsed.rewrittenQuestion(), parsed.subQuestions());
                return parsed;
            }
        } catch (Exception e) {
            log.warn("查询改写+拆分 LLM 调用失败，question={}", originalQuestion, e);
        }
        return null;
    }

    private RewriteResult callLLMRewriteAndSplitWithHistory(String normalizedQuestion,
                                                            String originalQuestion,
                                                            List<ChatMessage> history) {
        String historyText = buildHistoryContext(history);
        String prompt = RAGEnterpriseConstant.QUERY_REWRITE_AND_SPLIT_WITH_HISTORY_PROMPT.formatted(historyText, normalizedQuestion);

        ChatRequest req = ChatRequest.builder()
                .prompt(prompt)
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();

        try {
            String raw = llmService.chat(req);
            RewriteResult parsed = parseRewriteAndSplit(raw);
            if (parsed != null) {
                log.info("""
                        查询改写+拆分（带历史）
                        原始问题：{}
                        归一化后：{}
                        改写结果：{}
                        子问题：{}
                        """, originalQuestion, normalizedQuestion, parsed.rewrittenQuestion(), parsed.subQuestions());
                return parsed;
            }
        } catch (Exception e) {
            log.warn("查询改写+拆分（带历史）LLM 调用失败，question={}", originalQuestion, e);
        }
        return null;
    }

    private boolean shouldUseHistoryForRewrite(String question, List<ChatMessage> history) {
        if (StrUtil.isBlank(question) || CollUtil.isEmpty(history)) {
            return false;
        }
        String trimmed = question.trim();
        Integer threshold = ragConfigProperties.getQueryRewriteShortQueryThreshold();
        int limit = threshold == null ? 0 : threshold;
        return limit > 0 && trimmed.length() <= limit;
    }

    private String buildHistoryContext(List<ChatMessage> history) {
        if (CollUtil.isEmpty(history)) {
            return "";
        }
        Integer maxMessages = ragConfigProperties.getQueryRewriteMaxHistoryMessages();
        int limit = maxMessages == null ? 0 : maxMessages;
        List<ChatMessage> slice = history;
        if (limit > 0) {
            slice = pickLatestUserMessages(history, limit);
        }
        StringBuilder sb = new StringBuilder();
        int maxChars = resolveMaxHistoryChars();
        for (ChatMessage message : slice) {
            if (message == null || StrUtil.isBlank(message.getContent())) {
                continue;
            }
            if (message.getRole() != ChatMessage.Role.USER) {
                continue;
            }
            String line = toRoleLabel(message.getRole()) + message.getContent().trim() + "\n";
            if (maxChars > 0 && sb.length() + line.length() > maxChars) {
                break;
            }
            sb.append(line);
        }
        return sb.toString().trim();
    }

    private String toRoleLabel(ChatMessage.Role role) {
        if (role == null) {
            return "";
        }
        return switch (role) {
            case USER -> "用户：";
            case ASSISTANT -> "助手：";
            case SYSTEM -> "系统：";
        };
    }

    private List<ChatMessage> pickLatestUserMessages(List<ChatMessage> history, int limit) {
        if (CollUtil.isEmpty(history) || limit <= 0) {
            return List.of();
        }
        List<ChatMessage> users = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            if (msg != null && msg.getRole() == ChatMessage.Role.USER && StrUtil.isNotBlank(msg.getContent())) {
                users.add(msg);
                if (users.size() >= limit) {
                    break;
                }
            }
        }
        if (users.isEmpty()) {
            return List.of();
        }
        Collections.reverse(users);
        return users;
    }

    private int resolveMaxHistoryChars() {
        Integer maxChars = ragConfigProperties.getQueryRewriteMaxHistoryChars();
        return maxChars == null ? 0 : maxChars;
    }

    private RewriteResult parseRewriteAndSplit(String raw) {
        try {
            JsonElement root = JsonParser.parseString(raw.trim());
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            String rewrite = obj.has("rewrite") ? obj.get("rewrite").getAsString().trim() : "";
            List<String> subs = new ArrayList<>();
            if (obj.has("sub_questions") && obj.get("sub_questions").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("sub_questions");
                for (JsonElement el : arr) {
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                        String s = el.getAsString().trim();
                        if (StrUtil.isNotBlank(s)) {
                            subs.add(s);
                        }
                    }
                }
            }
            if (StrUtil.isBlank(rewrite)) {
                return null;
            }
            if (CollUtil.isEmpty(subs)) {
                subs = List.of(rewrite);
            }
            return new RewriteResult(rewrite, subs);
        } catch (Exception e) {
            log.warn("解析改写+拆分结果失败，raw={}", raw, e);
            return null;
        }
    }

    private List<String> ruleBasedSplit(String question) {
        // 兜底：按常见分隔符拆分
        List<String> parts = Arrays.stream(question.split("[?？。；;\\n]+"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());

        if (CollUtil.isEmpty(parts)) {
            return List.of(question);
        }
        return parts.stream()
                .map(s -> s.endsWith("？") || s.endsWith("?") ? s : s + "？")
                .toList();
    }
}

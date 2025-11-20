package com.nageoffer.ai.ragent.core.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话会话管理服务 - 实现记忆功能
 */
@Service
public class ConversationService {

    // 存储所有会话：sessionId -> 消息列表
    private final Map<String, List<Message>> sessions = new ConcurrentHashMap<>();

    // 每个会话最多保留的历史消息数量
    private static final int MAX_HISTORY = 10;

    /**
     * 添加消息到会话
     */
    public void addMessage(String sessionId, String role, String content) {
        sessions.computeIfAbsent(sessionId, k -> new ArrayList<>())
                .add(new Message(role, content));

        // 限制历史消息数量，避免 token 过多
        List<Message> messages = sessions.get(sessionId);
        if (messages.size() > MAX_HISTORY * 2) {
            // 保留最近的消息
            sessions.put(sessionId, new ArrayList<>(
                    messages.subList(messages.size() - MAX_HISTORY * 2, messages.size())
            ));
        }
    }

    /**
     * 获取会话的所有历史消息
     */
    public List<Message> getHistory(String sessionId) {
        return sessions.getOrDefault(sessionId, new ArrayList<>());
    }

    /**
     * 构建包含历史的完整对话上下文
     */
    public String buildContextWithHistory(String sessionId, String ragContext, String currentQuestion) {
        List<Message> history = getHistory(sessionId);

        StringBuilder context = new StringBuilder();
        context.append("你是一个专业的知识库问答助手，请根据以下文档和对话历史回答用户问题。\n\n");

        // 添加 RAG 检索到的文档片段
        if (ragContext != null && !ragContext.isEmpty()) {
            context.append("【检索到的文档片段】:\n")
                    .append(ragContext)
                    .append("\n\n");
        }

        // 添加对话历史
        if (!history.isEmpty()) {
            context.append("【对话历史】:\n");
            for (Message msg : history) {
                if ("user".equals(msg.role)) {
                    context.append("用户: ").append(msg.content).append("\n");
                } else if ("assistant".equals(msg.role)) {
                    context.append("助手: ").append(msg.content).append("\n");
                }
            }
            context.append("\n");
        }

        // 添加当前问题
        context.append("【当前问题】:\n")
                .append(currentQuestion)
                .append("\n\n请给出简洁、准确的回答：");

        return context.toString();
    }

    /**
     * 清除会话
     */
    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * 获取所有会话 ID
     */
    public Set<String> getAllSessionIds() {
        return sessions.keySet();
    }

    /**
     * 消息对象
     */
    public static class Message {
        public final String role;      // "user" 或 "assistant"
        public final String content;   // 消息内容
        public final long timestamp;   // 时间戳

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}

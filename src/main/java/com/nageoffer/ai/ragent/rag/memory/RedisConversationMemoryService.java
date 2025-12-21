package com.nageoffer.ai.ragent.rag.memory;

import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.nageoffer.ai.ragent.config.MemoryProperties;
import com.nageoffer.ai.ragent.convention.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RedisConversationMemoryService implements ConversationMemoryService {

    private static final String KEY_PREFIX = "ragent:memory:";

    private final StringRedisTemplate stringRedisTemplate;
    private final MemoryProperties memoryProperties;
    private final Gson gson = new Gson();

    @Override
    public List<ChatMessage> load(String sessionId, String userId, int maxMessages) {
        String key = buildKey(sessionId, userId);
        if (key == null) {
            return List.of();
        }
        long size = Math.max(maxMessages, 0);
        if (size == 0) {
            return List.of();
        }
        List<String> raw = stringRedisTemplate.opsForList().range(key, -size, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> result = new ArrayList<>();
        for (String item : raw) {
            if (StrUtil.isBlank(item)) {
                continue;
            }
            result.add(gson.fromJson(item, ChatMessage.class));
        }
        return result;
    }

    @Override
    public void append(String sessionId, String userId, ChatMessage message) {
        String key = buildKey(sessionId, userId);
        if (key == null || message == null) {
            return;
        }
        String payload = gson.toJson(message);
        stringRedisTemplate.opsForList().rightPush(key, payload);
        int ttlMinutes = memoryProperties.getTtlMinutes();
        if (ttlMinutes > 0) {
            stringRedisTemplate.expire(key, Duration.ofMinutes(ttlMinutes));
        }
    }

    private String buildKey(String sessionId, String userId) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        String safeUserId = StrUtil.isBlank(userId) ? "anon" : userId.trim();
        return KEY_PREFIX + safeUserId + ":" + sessionId.trim();
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.memory.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.core.memory.JdbcConversationMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis 工作记忆存储。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisWorkingMemoryStore implements WorkingMemoryStore {

    private static final String KEY_PREFIX = "ragent:memory:working:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final JdbcConversationMemoryStore jdbcConversationMemoryStore;
    private final MemoryProperties memoryProperties;

    @Override
    public List<ChatMessage> load(String conversationId, String userId) {
        String key = buildKey(conversationId, userId);
        try {
            String cacheJson = stringRedisTemplate.opsForValue().get(key);
            if (cacheJson != null && !cacheJson.isBlank()) {
                return objectMapper.readValue(cacheJson, new TypeReference<>() {
                });
            }
        } catch (Exception ex) {
            log.warn("Load working memory from redis failed, key={}", key, ex);
        }
        List<ChatMessage> history = jdbcConversationMemoryStore.loadHistory(conversationId, userId);
        cache(key, history);
        return history;
    }

    @Override
    public void append(String conversationId, String userId, ChatMessage message) {
        List<ChatMessage> histories = new ArrayList<>(load(conversationId, userId));
        histories.add(message);
        cache(buildKey(conversationId, userId), trim(histories));
    }

    @Override
    public void refresh(String conversationId, String userId) {
        cache(buildKey(conversationId, userId), jdbcConversationMemoryStore.loadHistory(conversationId, userId));
    }

    private void cache(String key, List<ChatMessage> messages) {
        try {
            stringRedisTemplate.opsForValue().set(
                    key,
                    objectMapper.writeValueAsString(trim(messages)),
                    memoryProperties.getWorkingCacheTtlMinutes(),
                    TimeUnit.MINUTES
            );
        } catch (Exception ex) {
            log.warn("Cache working memory failed, key={}", key, ex);
        }
    }

    private List<ChatMessage> trim(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int maxMessages = memoryProperties.getWorkingKeepTurns() * 2;
        if (messages.size() <= maxMessages) {
            return messages;
        }
        return new ArrayList<>(messages.subList(messages.size() - maxMessages, messages.size()));
    }

    private String buildKey(String conversationId, String userId) {
        return KEY_PREFIX + userId + ":" + conversationId;
    }
}

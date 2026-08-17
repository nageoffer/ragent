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

package com.nageoffer.ai.ragent.rag.core.prompt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 智能体提示词缓存管理器
 * 缓存的是激活智能体叠加自定义提示词之后的结果，命中即可直接取用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentPromptCacheManager {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CACHE_KEY = "ragent:agent:resolved-prompts";

    private static final long CACHE_EXPIRE_HOURS = 1;

    /**
     * @return 槽位到提示词的映射，缓存不存在则返回 null
     */
    public Map<String, String> getFromCache() {
        try {
            String cacheJson = stringRedisTemplate.opsForValue().get(CACHE_KEY);
            if (cacheJson == null) {
                return null;
            }
            return objectMapper.readValue(cacheJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.error("从 Redis 读取智能体提示词缓存失败", e);
            return null;
        }
    }

    public void saveToCache(Map<String, String> prompts) {
        try {
            String cacheJson = objectMapper.writeValueAsString(prompts);
            stringRedisTemplate.opsForValue().set(CACHE_KEY, cacheJson, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("保存智能体提示词到 Redis 缓存失败", e);
        }
    }

    /**
     * 任何智能体或槽位写操作后必须调用，否则改动直到过期才生效
     */
    public void clearCache() {
        try {
            stringRedisTemplate.delete(CACHE_KEY);
            log.info("智能体提示词缓存已清除");
        } catch (Exception e) {
            log.error("清除智能体提示词缓存失败", e);
        }
    }
}

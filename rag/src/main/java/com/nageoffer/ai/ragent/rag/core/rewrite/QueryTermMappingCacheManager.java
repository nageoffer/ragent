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

package com.nageoffer.ai.ragent.rag.core.rewrite;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.dao.entity.QueryTermMappingDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 术语映射缓存管理器
 * 负责术语映射规则在 Redis 中的缓存管理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryTermMappingCacheManager {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CACHE_KEY = "ragent:query-term:mappings";

    /**
     * 缓存过期时间：7天
     */
    private static final long CACHE_EXPIRE_DAYS = 7;

    /**
     * 从 Redis 获取术语映射缓存
     *
     * @return 映射规则列表，缓存不存在则返回 null
     */
    public List<QueryTermMappingDO> getMappingsFromCache() {
        try {
            String cacheJson = stringRedisTemplate.opsForValue().get(CACHE_KEY);
            if (cacheJson == null) {
                log.info("术语映射缓存不存在，需要从数据库加载");
                return null;
            }
            return objectMapper.readValue(cacheJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.error("从 Redis 读取术语映射缓存失败", e);
            return null;
        }
    }

    /**
     * 将术语映射保存到 Redis 缓存
     *
     * @param mappings 映射规则列表（已排序）
     */
    public void saveMappingsToCache(List<QueryTermMappingDO> mappings) {
        try {
            String cacheJson = objectMapper.writeValueAsString(mappings);
            stringRedisTemplate.opsForValue().set(CACHE_KEY, cacheJson, CACHE_EXPIRE_DAYS, TimeUnit.DAYS);
            log.info("术语映射已保存到 Redis 缓存，共 {} 条规则", mappings.size());
        } catch (Exception e) {
            log.error("保存术语映射到 Redis 缓存失败", e);
        }
    }

    /**
     * 清除术语映射缓存
     * 在映射规则发生增删改时调用
     */
    public void clearCache() {
        Boolean deleted = stringRedisTemplate.delete(CACHE_KEY);
        if (deleted) {
            log.info("术语映射缓存已清除");
        } else {
            log.info("术语映射缓存不存在，无需清除");
        }
    }
}

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

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.dao.entity.QueryTermMappingDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.QueryTermMappingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryTermMappingService {

    private final QueryTermMappingMapper mappingMapper;
    private final QueryTermMappingCacheManager cacheManager;

    /**
     * 对用户问题做术语归一化
     */
    public String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        List<QueryTermMappingDO> mappings = loadMappings();
        if (mappings.isEmpty()) {
            return text;
        }

        String result = text;
        for (QueryTermMappingDO mapping : mappings) {
            if (mapping.getEnabled() == null || mapping.getEnabled() == 0) {
                continue;
            }
            if (mapping.getMatchType() != null && mapping.getMatchType() != 1) {
                continue;
            }
            String source = mapping.getSourceTerm();
            String target = mapping.getTargetTerm();
            if (source == null || source.isEmpty() || target == null || target.isEmpty()) {
                continue;
            }
            result = QueryTermMappingUtil.applyMapping(result, source, target);
        }

        if (!Objects.equals(text, result)) {
            log.info("查询归一化：original='{}', normalized='{}'", text, result);
        }
        return result;
    }

    /**
     * 加载映射规则：优先从 Redis 缓存读取，缓存未命中则从数据库加载并回填缓存
     */
    private List<QueryTermMappingDO> loadMappings() {
        List<QueryTermMappingDO> cached = cacheManager.getMappingsFromCache();
        if (CollUtil.isNotEmpty(cached)) {
            return cached;
        }

        // 缓存未命中，从数据库加载
        List<QueryTermMappingDO> dbList = mappingMapper.selectList(
                Wrappers.lambdaQuery(QueryTermMappingDO.class)
                        .eq(QueryTermMappingDO::getEnabled, 1)
        );
        dbList.sort(Comparator
                .comparing(QueryTermMappingDO::getPriority, Comparator.nullsLast(Integer::compareTo)).reversed()
                .thenComparing(m -> m.getSourceTerm() == null ? 0 : m.getSourceTerm().length(), Comparator.reverseOrder())
        );

        // 回填 Redis 缓存
        cacheManager.saveMappingsToCache(dbList);
        log.info("术语映射规则从数据库加载完成，共 {} 条规则", dbList.size());
        return dbList;
    }
}

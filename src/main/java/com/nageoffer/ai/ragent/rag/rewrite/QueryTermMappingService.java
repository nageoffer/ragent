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

package com.nageoffer.ai.ragent.rag.rewrite;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.dao.entity.QueryTermMappingDO;
import com.nageoffer.ai.ragent.dao.mapper.QueryTermMappingMapper;
import jakarta.annotation.PostConstruct;
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

    // 按优先级、长度缓存好的映射规则
    private volatile List<QueryTermMappingDO> cachedMappings = List.of();

    @PostConstruct
    public void loadMappings() {
        List<QueryTermMappingDO> dbList = mappingMapper.selectList(
                Wrappers.lambdaQuery(QueryTermMappingDO.class)
                        .eq(QueryTermMappingDO::getEnabled, 1)
        );
        // 建议：优先级高的在前，sourceTerm 更长的在前，避免短词先替换把长词打断
        dbList.sort(Comparator
                .comparing(QueryTermMappingDO::getPriority, Comparator.nullsLast(Integer::compareTo)).reversed()
                .thenComparing(m -> m.getSourceTerm() == null ? 0 : m.getSourceTerm().length(), Comparator.reverseOrder())
        );
        cachedMappings = dbList;

        log.info("查询归一化映射规则加载完成, 共加载 {} 条规则", cachedMappings.size());
    }

    /**
     * 对用户问题做术语归一化
     */
    public String normalize(String text) {
        if (text == null || text.isEmpty() || cachedMappings.isEmpty()) {
            return text;
        }
        String result = text;
        for (QueryTermMappingDO mapping : cachedMappings) {
            if (Boolean.FALSE.equals(mapping.getEnabled())) {
                continue;
            }
            if (mapping.getMatchType() != null && mapping.getMatchType() != 1) {
                // 这里只示例 match_type = 1 的简单子串匹配，其他类型可以自己扩展
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
}

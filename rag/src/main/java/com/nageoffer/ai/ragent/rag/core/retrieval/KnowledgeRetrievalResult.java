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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record KnowledgeRetrievalResult(List<RetrievedChunk> chunks,
                                       Map<String, Set<String>> intentIdsByChunkKey,
                                       Set<String> directedIntentIds) {

    public KnowledgeRetrievalResult {
        chunks = chunks == null ? List.of() : chunks;
        intentIdsByChunkKey = intentIdsByChunkKey == null ? Map.of() : intentIdsByChunkKey;
        directedIntentIds = directedIntentIds == null
                ? Set.of()
                : Set.copyOf(directedIntentIds);
    }

    public static KnowledgeRetrievalResult empty() {
        return new KnowledgeRetrievalResult(List.of(), Map.of(), Set.of());
    }

    public Set<String> retrievedIntentIds() {
        Set<String> intentIds = new LinkedHashSet<>();
        intentIdsByChunkKey.values().stream()
                .filter(Objects::nonNull)
                .forEach(intentIds::addAll);
        return Collections.unmodifiableSet(intentIds);
    }

    public Set<String> eligibleIntentIds(List<NodeScore> candidateIntents) {
        Set<String> retrievedIntentIds = retrievedIntentIds();
        return candidateIntents.stream()
                .filter(Objects::nonNull)
                .map(NodeScore::getNode)
                .filter(Objects::nonNull)
                .map(IntentNode::getId)
                .filter(StrUtil::isNotBlank)
                .filter(intentId -> !directedIntentIds.contains(intentId)
                        || retrievedIntentIds.contains(intentId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Map<String, List<RetrievedChunk>> groupByIntent(String globalKey) {
        Map<String, List<RetrievedChunk>> grouped = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            Set<String> intentIds = intentIdsByChunkKey.get(RetrievedChunkKey.of(chunk));
            if (intentIds == null || intentIds.isEmpty()) {
                grouped.computeIfAbsent(globalKey, ignored -> new ArrayList<>()).add(chunk);
                continue;
            }
            for (String intentId : intentIds) {
                grouped.computeIfAbsent(intentId, ignored -> new ArrayList<>()).add(chunk);
            }
        }
        return grouped;
    }
}

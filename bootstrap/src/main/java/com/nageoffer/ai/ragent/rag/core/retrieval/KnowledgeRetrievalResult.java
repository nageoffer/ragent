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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record KnowledgeRetrievalResult(List<RetrievedChunk> chunks,
                                       Map<String, Set<String>> intentIdsByChunkKey) {

    public KnowledgeRetrievalResult {
        chunks = chunks == null ? List.of() : chunks;
        intentIdsByChunkKey = intentIdsByChunkKey == null ? Map.of() : intentIdsByChunkKey;
    }

    public static KnowledgeRetrievalResult empty() {
        return new KnowledgeRetrievalResult(List.of(), Map.of());
    }

    public Set<String> retrievedIntentIds() {
        Set<String> intentIds = new LinkedHashSet<>();
        intentIdsByChunkKey.values().stream()
                .filter(Objects::nonNull)
                .forEach(intentIds::addAll);
        return Collections.unmodifiableSet(intentIds);
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

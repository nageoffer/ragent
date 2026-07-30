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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次检索请求内的 Chunk 真实召回意图索引。
 *
 * <p>归属在仍然知道目标意图的定向检索处产生，并以稳定 Chunk key 传过后处理链。实例深度不可变，避免请求间共享或后续误改。
 */
public final class IntentChunkAttribution {

    private static final IntentChunkAttribution EMPTY = new IntentChunkAttribution(Map.of());

    private final Map<String, Set<String>> intentIdsByChunkKey;

    private IntentChunkAttribution(Map<String, ? extends Collection<String>> source) {
        Map<String, Set<String>> copied = new LinkedHashMap<>();
        source.forEach((chunkKey, intentIds) -> {
            if (chunkKey == null || intentIds == null || intentIds.isEmpty()) {
                return;
            }
            LinkedHashSet<String> validIds = new LinkedHashSet<>();
            intentIds.stream()
                    .filter(intentId -> intentId != null && !intentId.isBlank())
                    .forEach(validIds::add);
            if (!validIds.isEmpty()) {
                copied.put(chunkKey, Collections.unmodifiableSet(validIds));
            }
        });
        this.intentIdsByChunkKey = Collections.unmodifiableMap(copied);
    }

    public static IntentChunkAttribution empty() {
        return EMPTY;
    }

    /**
     * 从按意图分组的原始定向召回结果构建归属；同一 Chunk 可累积多个真实意图。
     */
    public static IntentChunkAttribution fromIntentChunks(Map<String, List<RetrievedChunk>> chunksByIntent) {
        if (chunksByIntent == null || chunksByIntent.isEmpty()) {
            return empty();
        }
        Map<String, Set<String>> index = new LinkedHashMap<>();
        chunksByIntent.forEach((intentId, chunks) -> {
            if (intentId == null || intentId.isBlank() || chunks == null) {
                return;
            }
            for (RetrievedChunk chunk : chunks) {
                if (chunk != null) {
                    index.computeIfAbsent(RetrievedChunkKey.of(chunk), ignored -> new LinkedHashSet<>())
                            .add(intentId);
                }
            }
        });
        return index.isEmpty() ? empty() : new IntentChunkAttribution(index);
    }

    /**
     * 合并多个通道携带的归属，保留首次出现的稳定顺序。
     */
    public static IntentChunkAttribution merge(Collection<IntentChunkAttribution> attributions) {
        if (attributions == null || attributions.isEmpty()) {
            return empty();
        }
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        for (IntentChunkAttribution attribution : attributions) {
            if (attribution == null) {
                continue;
            }
            attribution.intentIdsByChunkKey.forEach((chunkKey, intentIds) ->
                    merged.computeIfAbsent(chunkKey, ignored -> new LinkedHashSet<>()).addAll(intentIds));
        }
        return merged.isEmpty() ? empty() : new IntentChunkAttribution(merged);
    }

    /**
     * 只保留后处理后仍存活的 Chunk 归属。
     */
    public IntentChunkAttribution retain(List<RetrievedChunk> retainedChunks) {
        if (retainedChunks == null || retainedChunks.isEmpty() || intentIdsByChunkKey.isEmpty()) {
            return empty();
        }
        Map<String, Set<String>> retained = new LinkedHashMap<>();
        for (RetrievedChunk chunk : retainedChunks) {
            String chunkKey = RetrievedChunkKey.of(chunk);
            Set<String> intentIds = intentIdsByChunkKey.get(chunkKey);
            if (intentIds != null && !intentIds.isEmpty()) {
                retained.put(chunkKey, intentIds);
            }
        }
        return retained.isEmpty() ? empty() : new IntentChunkAttribution(retained);
    }

    /**
     * 按最终 Chunk 顺序重建意图分组；无可靠归属的证据统一进入全局特殊 key。
     */
    public Map<String, List<RetrievedChunk>> groupRetainedChunks(List<RetrievedChunk> retainedChunks,
                                                                 String globalKey) {
        if (retainedChunks == null || retainedChunks.isEmpty()) {
            return Map.of();
        }
        Map<String, List<RetrievedChunk>> grouped = new LinkedHashMap<>();
        for (RetrievedChunk chunk : retainedChunks) {
            Set<String> intentIds = intentIdsByChunkKey.get(RetrievedChunkKey.of(chunk));
            if (intentIds == null || intentIds.isEmpty()) {
                grouped.computeIfAbsent(globalKey, ignored -> new ArrayList<>()).add(chunk);
                continue;
            }
            for (String intentId : intentIds) {
                grouped.computeIfAbsent(intentId, ignored -> new ArrayList<>()).add(chunk);
            }
        }

        Map<String, List<RetrievedChunk>> immutable = new LinkedHashMap<>();
        grouped.forEach((intentId, chunks) -> immutable.put(intentId, List.copyOf(chunks)));
        return Collections.unmodifiableMap(immutable);
    }
}

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

import java.util.List;
import java.util.Map;

/**
 * 多通道后处理完成后的最终 Chunk 与其真实意图归属。
 */
public record KnowledgeRetrievalResult(List<RetrievedChunk> chunks,
                                       IntentChunkAttribution intentAttribution) {

    public KnowledgeRetrievalResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        intentAttribution = intentAttribution == null ? IntentChunkAttribution.empty() : intentAttribution;
    }

    public static KnowledgeRetrievalResult empty() {
        return new KnowledgeRetrievalResult(List.of(), IntentChunkAttribution.empty());
    }

    public Map<String, List<RetrievedChunk>> groupByIntent(String globalKey) {
        return intentAttribution.groupRetainedChunks(chunks, globalKey);
    }
}

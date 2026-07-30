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
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IntentChunkAttributionTest {

    @Test
    void sharedChunkRetainsEveryActualIntent() {
        RetrievedChunk shared = chunk("shared", "共享资料");
        Map<String, List<RetrievedChunk>> byIntent = new LinkedHashMap<>();
        byIntent.put("A", List.of(shared));
        byIntent.put("B", List.of(shared));

        Map<String, List<RetrievedChunk>> grouped = IntentChunkAttribution.fromIntentChunks(byIntent)
                .groupRetainedChunks(List.of(shared), "multi_channel");

        assertEquals(List.of(shared), grouped.get("A"));
        assertEquals(List.of(shared), grouped.get("B"));
        assertFalse(grouped.containsKey("multi_channel"));
    }

    @Test
    void missingIdUsesStableTextDigestAcrossInstances() {
        RetrievedChunk original = chunk(null, "无ID资料");
        RetrievedChunk retainedCopy = chunk(null, "无ID资料");

        IntentChunkAttribution attribution =
                IntentChunkAttribution.fromIntentChunks(Map.of("A", List.of(original)));
        Map<String, List<RetrievedChunk>> grouped =
                attribution.groupRetainedChunks(List.of(retainedCopy), "multi_channel");

        assertEquals(List.of(retainedCopy), grouped.get("A"));
    }

    @Test
    void discardedChunkAttributionIsRemoved() {
        RetrievedChunk retained = chunk("kept", "保留");
        RetrievedChunk discarded = chunk("discarded", "淘汰");
        IntentChunkAttribution attribution = IntentChunkAttribution.fromIntentChunks(Map.of(
                "A", List.of(discarded),
                "B", List.of(retained)
        )).retain(List.of(retained));

        Map<String, List<RetrievedChunk>> grouped =
                attribution.groupRetainedChunks(List.of(retained), "multi_channel");

        assertFalse(grouped.containsKey("A"));
        assertEquals(List.of(retained), grouped.get("B"));
    }

    @Test
    void intentListsFollowFinalPostProcessingOrder() {
        RetrievedChunk first = chunk("first", "原始第一");
        RetrievedChunk second = chunk("second", "原始第二");
        IntentChunkAttribution attribution =
                IntentChunkAttribution.fromIntentChunks(Map.of("A", List.of(first, second)));

        Map<String, List<RetrievedChunk>> grouped =
                attribution.groupRetainedChunks(List.of(second, first), "multi_channel");

        assertEquals(List.of(second, first), grouped.get("A"));
    }

    private RetrievedChunk chunk(String id, String text) {
        return RetrievedChunk.builder().id(id).text(text).build();
    }
}

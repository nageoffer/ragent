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

package com.nageoffer.ai.ragent.rag.memory;

import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ForgettingController;
import com.nageoffer.ai.ragent.rag.core.memory.MemoryGovernanceJob;
import com.nageoffer.ai.ragent.rag.core.memory.MemoryQualityAssessor;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryItem;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryLayer;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryQualityReport;
import com.nageoffer.ai.ragent.rag.core.memory.store.LongTermMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.store.SemanticMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.store.ShortTermMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryGovernanceJobTests {

    @Test
    void shouldPromoteMemoriesAndAssessQuality() {
        ShortTermMemoryStore shortTermMemoryStore = mock(ShortTermMemoryStore.class);
        LongTermMemoryStore longTermMemoryStore = mock(LongTermMemoryStore.class);
        SemanticMemoryStore semanticMemoryStore = mock(SemanticMemoryStore.class);
        ForgettingController forgettingController = mock(ForgettingController.class);
        MemoryQualityAssessor memoryQualityAssessor = mock(MemoryQualityAssessor.class);

        MemoryProperties properties = new MemoryProperties();
        properties.setLongTermImportanceThreshold(0.7D);
        properties.setQualityAssessmentEnabled(true);

        MemoryItem profile = MemoryItem.builder()
                .userId("u1")
                .conversationId("c1")
                .layer(MemoryLayer.SHORT_TERM)
                .type("PROFILE")
                .content("我是 Java 开发")
                .sourceIdsJson("[\"m1\"]")
                .importanceScore(0.9D)
                .confidenceLevel(0.95D)
                .build();
        MemoryItem fact = MemoryItem.builder()
                .userId("u1")
                .conversationId("c1")
                .layer(MemoryLayer.SHORT_TERM)
                .type("FACT")
                .content("偏好流式输出")
                .importanceScore(0.72D)
                .confidenceLevel(0.8D)
                .build();

        when(shortTermMemoryStore.listPromotable(eq(""), eq(0D))).thenReturn(List.of(profile));
        when(shortTermMemoryStore.listPromotable(eq("u1"), eq(0.7D))).thenReturn(List.of(profile, fact));
        when(memoryQualityAssessor.assess("u1")).thenReturn(MemoryQualityReport.builder().userId("u1").build());

        MemoryGovernanceJob job = new MemoryGovernanceJob(
                shortTermMemoryStore,
                longTermMemoryStore,
                semanticMemoryStore,
                forgettingController,
                memoryQualityAssessor,
                properties
        );

        job.promoteAndAssess();

        verify(longTermMemoryStore, times(2)).save(any(MemoryItem.class));
        verify(semanticMemoryStore).upsert(profile, "我是 Java 开发");
        verify(memoryQualityAssessor).assess("u1");
        verify(forgettingController).execute();
    }

    @Test
    void shouldSkipQualityAssessmentWhenDisabled() {
        ShortTermMemoryStore shortTermMemoryStore = mock(ShortTermMemoryStore.class);
        LongTermMemoryStore longTermMemoryStore = mock(LongTermMemoryStore.class);
        SemanticMemoryStore semanticMemoryStore = mock(SemanticMemoryStore.class);
        ForgettingController forgettingController = mock(ForgettingController.class);
        MemoryQualityAssessor memoryQualityAssessor = mock(MemoryQualityAssessor.class);

        MemoryProperties properties = new MemoryProperties();
        properties.setLongTermImportanceThreshold(0.6D);
        properties.setQualityAssessmentEnabled(false);

        MemoryItem preference = MemoryItem.builder()
                .userId("u2")
                .conversationId("c2")
                .layer(MemoryLayer.SHORT_TERM)
                .type("PREFERENCE")
                .content("喜欢中文回复")
                .importanceScore(0.88D)
                .confidenceLevel(0.96D)
                .build();

        when(shortTermMemoryStore.listPromotable(eq(""), eq(0D))).thenReturn(List.of(preference));
        when(shortTermMemoryStore.listPromotable(eq("u2"), eq(0.6D))).thenReturn(List.of(preference));

        MemoryGovernanceJob job = new MemoryGovernanceJob(
                shortTermMemoryStore,
                longTermMemoryStore,
                semanticMemoryStore,
                forgettingController,
                memoryQualityAssessor,
                properties
        );

        job.promoteAndAssess();

        verify(longTermMemoryStore).save(any(MemoryItem.class));
        verify(semanticMemoryStore).upsert(preference, "喜欢中文回复");
        verify(memoryQualityAssessor, never()).assess(any());
        verify(forgettingController).execute();
    }
}

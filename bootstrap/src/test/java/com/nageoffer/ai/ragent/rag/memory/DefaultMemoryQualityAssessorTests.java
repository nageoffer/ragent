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

import com.nageoffer.ai.ragent.rag.core.memory.DefaultMemoryQualityAssessor;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryItem;
import com.nageoffer.ai.ragent.rag.core.memory.store.LongTermMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.store.SemanticMemoryStore;
import com.nageoffer.ai.ragent.rag.dao.entity.MemoryConflictLogDO;
import com.nageoffer.ai.ragent.rag.dao.entity.SemanticMemoryDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.MemoryConflictLogMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.SemanticMemoryMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ShortTermMemoryMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultMemoryQualityAssessorTests {

    @Test
    void shouldSkipInsertWhenConflictAlreadyExists() {
        ShortTermMemoryMapper shortTermMemoryMapper = mock(ShortTermMemoryMapper.class);
        LongTermMemoryStore longTermMemoryStore = mock(LongTermMemoryStore.class);
        SemanticMemoryStore semanticMemoryStore = mock(SemanticMemoryStore.class);
        SemanticMemoryMapper semanticMemoryMapper = mock(SemanticMemoryMapper.class);
        MemoryConflictLogMapper memoryConflictLogMapper = mock(MemoryConflictLogMapper.class);

        when(semanticMemoryMapper.selectList(any())).thenReturn(List.of(
                SemanticMemoryDO.builder()
                        .id("s1")
                        .userId("u1")
                        .semanticKey("中文回复")
                        .semanticType("PREFERENCE")
                        .valueJson("{\"source\":\"user\",\"content\":\"我喜欢中文回复\"}")
                        .build(),
                SemanticMemoryDO.builder()
                        .id("s2")
                        .userId("u1")
                        .semanticKey("不喜欢中文回复")
                        .semanticType("PREFERENCE")
                        .valueJson("{\"source\":\"user\",\"content\":\"我不喜欢中文回复\"}")
                        .build()
        ));
        when(memoryConflictLogMapper.selectCount(any())).thenReturn(1L, 1L, 0L);
        when(shortTermMemoryMapper.selectCount(any())).thenReturn(0L);
        when(longTermMemoryStore.listByUser("u1")).thenReturn(List.<MemoryItem>of());
        when(semanticMemoryStore.listByUser("u1")).thenReturn(List.<MemoryItem>of());

        DefaultMemoryQualityAssessor assessor = new DefaultMemoryQualityAssessor(
                shortTermMemoryMapper,
                longTermMemoryStore,
                semanticMemoryStore,
                semanticMemoryMapper,
                memoryConflictLogMapper
        );

        assessor.assess("u1");

        verify(memoryConflictLogMapper, never()).insert(any(MemoryConflictLogDO.class));
    }
}

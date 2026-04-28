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

import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryItem;
import com.nageoffer.ai.ragent.rag.core.memory.store.JdbcSemanticMemoryStore;
import com.nageoffer.ai.ragent.rag.dao.entity.SemanticMemoryDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.SemanticMemoryMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcSemanticMemoryStoreTests {

    @Test
    void shouldInsertSemanticMemoryWhenAbsent() {
        SemanticMemoryMapper mapper = mock(SemanticMemoryMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        JdbcSemanticMemoryStore store = new JdbcSemanticMemoryStore(mapper);

        store.upsert(MemoryItem.builder()
                .userId("u1")
                .type("PREFERENCE")
                .content("中文回复")
                .metadataJson("{\"source\":\"user\",\"content\":\"我喜欢中文回复\"}")
                .sourceIdsJson("[\"m1\"]")
                .confidenceLevel(0.95D)
                .build(), "中文回复");

        ArgumentCaptor<SemanticMemoryDO> captor = ArgumentCaptor.forClass(SemanticMemoryDO.class);
        verify(mapper).insert(captor.capture());
        Assertions.assertEquals("中文回复", captor.getValue().getSemanticKey());
        Assertions.assertEquals("[\"m1\"]", captor.getValue().getSourceMemoryIds());
    }

    @Test
    void shouldKeepExistingUserSemanticValueWhenIncomingSourceIsWeaker() {
        SemanticMemoryMapper mapper = mock(SemanticMemoryMapper.class);
        when(mapper.selectOne(any())).thenReturn(SemanticMemoryDO.builder()
                .id("s1")
                .semanticKey("中文回复")
                .semanticType("PREFERENCE")
                .valueJson("{\"source\":\"user\",\"content\":\"我喜欢中文回复\"}")
                .confidenceLevel(0.95D)
                .build());
        JdbcSemanticMemoryStore store = new JdbcSemanticMemoryStore(mapper);

        store.upsert(MemoryItem.builder()
                .userId("u1")
                .type("PREFERENCE")
                .content("中文回复")
                .metadataJson("{\"source\":\"assistant\",\"content\":\"建议使用英文\"}")
                .sourceIdsJson("[\"m2\"]")
                .confidenceLevel(0.6D)
                .build(), "中文回复");

        ArgumentCaptor<SemanticMemoryDO> captor = ArgumentCaptor.forClass(SemanticMemoryDO.class);
        verify(mapper).update(captor.capture(), any());
        Assertions.assertNull(captor.getValue().getValueJson());
        Assertions.assertEquals(0.95D, captor.getValue().getConfidenceLevel());
        verify(mapper, never()).insert(any(SemanticMemoryDO.class));
    }
}

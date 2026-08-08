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

package com.nageoffer.ai.ragent.knowledge.sink;

import com.nageoffer.ai.ragent.core.ingest.DocumentRef;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证首个关系库落点在接触 chunk 前锁定文档，并拒绝已失去 RUNNING 状态的任务。 */
@ExtendWith(MockitoExtension.class)
class RelationalChunkSinkTest {

    private static final DocumentRef DOC = new DocumentRef("doc-1", "kb-1", "guide.md");
    private static final VectorTarget TARGET = new VectorTarget("kb", "embedding", 3);

    @Mock
    private KnowledgeChunkMapper chunkMapper;
    @Mock
    private TokenCounterService tokenCounterService;
    @Mock
    private KnowledgeDocumentMapper documentMapper;

    @InjectMocks
    private RelationalChunkSink sink;

    @Test
    void locksAndVerifiesRunningStatusBeforeReplacingChunks() {
        when(documentMapper.selectStatusForUpdate("doc-1")).thenReturn(DocumentStatus.RUNNING.getCode());

        sink.replaceDocument(TARGET, DOC, List.of());

        InOrder order = inOrder(documentMapper, chunkMapper);
        order.verify(documentMapper).selectStatusForUpdate("doc-1");
        order.verify(chunkMapper).delete(any());
    }

    @Test
    void rejectsStaleTaskBeforeTouchingChunks() {
        when(documentMapper.selectStatusForUpdate("doc-1")).thenReturn(DocumentStatus.DELETING.getCode());

        assertThatThrownBy(() -> sink.replaceDocument(TARGET, DOC, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("doc-1")
                .hasMessageContaining(DocumentStatus.DELETING.getCode());

        verifyNoInteractions(chunkMapper);
    }
}

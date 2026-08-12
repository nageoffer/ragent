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

package com.nageoffer.ai.ragent.knowledge.mq;

import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentCleanupEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeDocumentCleanupTransactionCheckerTest {

    @Test
    void commitsOnlyWhenDocumentIsMissingOrLogicallyDeleted() {
        KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentCleanupTransactionChecker checker = new KnowledgeDocumentCleanupTransactionChecker(
                mapper, mock(DelegatingTransactionListener.class));
        MessageWrapper<KnowledgeDocumentCleanupEvent> message = MessageWrapper.<KnowledgeDocumentCleanupEvent>builder()
                .body(KnowledgeDocumentCleanupEvent.builder().docId("doc-1").build())
                .build();

        when(mapper.selectById("doc-1")).thenReturn(KnowledgeDocumentDO.builder()
                .id("doc-1")
                .deleted(0)
                .build());
        assertThat(checker.check(message)).isFalse();

        when(mapper.selectById("doc-1")).thenReturn(KnowledgeDocumentDO.builder()
                .id("doc-1")
                .deleted(1)
                .build());
        assertThat(checker.check(message)).isTrue();

        when(mapper.selectById("doc-1")).thenReturn(null);
        assertThat(checker.check(message)).isTrue();
    }
}

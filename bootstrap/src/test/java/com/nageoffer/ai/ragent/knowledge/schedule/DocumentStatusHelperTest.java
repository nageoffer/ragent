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

package com.nageoffer.ai.ragent.knowledge.schedule;

import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证分块、删除和完成/失败写回使用了正确的 CAS 来源状态及目标状态。 */
@ExtendWith(MockitoExtension.class)
class DocumentStatusHelperTest {

    @Mock
    private KnowledgeDocumentMapper documentMapper;

    @InjectMocks
    private DocumentStatusHelper helper;

    @Test
    void tryStartChunkAllowsAllStableStatuses() {
        when(documentMapper.casStatus(eq("doc-1"), anyList(), eq(DocumentStatus.RUNNING.getCode()), eq("alice")))
                .thenReturn(1);

        assertThat(helper.tryStartChunk("doc-1", "alice")).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> statuses = ArgumentCaptor.forClass(List.class);
        verify(documentMapper).casStatus(eq("doc-1"), statuses.capture(),
                eq(DocumentStatus.RUNNING.getCode()), eq("alice"));
        assertThat(statuses.getValue()).containsExactly(
                DocumentStatus.PENDING.getCode(),
                DocumentStatus.FAILED.getCode(),
                DocumentStatus.SUCCESS.getCode()
        );
    }

    @Test
    void tryMarkDeletingReturnsFalseOnConflict() {
        when(documentMapper.casStatus(eq("doc-1"), anyList(), eq(DocumentStatus.DELETING.getCode()), eq("alice")))
                .thenReturn(0);

        assertThat(helper.tryMarkDeleting("doc-1", "alice")).isFalse();
    }

    @Test
    void tryMarkSuccessUpdatesStatusAndChunkCountTogether() {
        when(documentMapper.markSuccessIfRunning(
                "doc-1", 3, "alice", DocumentStatus.RUNNING.getCode(), DocumentStatus.SUCCESS.getCode()))
                .thenReturn(1);

        assertThat(helper.tryMarkSuccess("doc-1", 3, "alice")).isTrue();
    }

    @Test
    void tryMarkFailedOnlyTransitionsFromRunning() {
        when(documentMapper.casStatus(eq("doc-1"), anyList(), eq(DocumentStatus.FAILED.getCode()), eq("alice")))
                .thenReturn(1);

        assertThat(helper.tryMarkFailed("doc-1", "alice")).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> statuses = ArgumentCaptor.forClass(List.class);
        verify(documentMapper).casStatus(eq("doc-1"), statuses.capture(),
                eq(DocumentStatus.FAILED.getCode()), eq("alice"));
        assertThat(statuses.getValue()).containsExactly(DocumentStatus.RUNNING.getCode());
    }
}

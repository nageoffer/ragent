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

package com.nageoffer.ai.ragent.knowledge.service;

import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.schedule.DocumentStatusHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentDeleteRaceTest {

    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @InjectMocks
    private DocumentStatusHelper documentStatusHelper;

    @Test
    void tryMarkDeleting_succeedsWhenIdle() {
        when(documentMapper.casStatus(eq("doc-1"), anyList(), eq(DocumentStatus.DELETING.getCode())))
                .thenReturn(1);
        assertThat(documentStatusHelper.tryMarkDeleting("doc-1")).isTrue();
    }

    @Test
    void tryMarkDeleting_failsWhenRunning() {
        // 模拟分块已抢占（CAS 命中 0 行）
        when(documentMapper.casStatus(eq("doc-1"), anyList(), eq(DocumentStatus.DELETING.getCode())))
                .thenReturn(0);
        assertThat(documentStatusHelper.tryMarkDeleting("doc-1")).isFalse();
    }

    @Test
    void tryStartChunk_failsWhenDeleting() {
        when(documentMapper.casStatus(eq("doc-1"), anyList(), eq(DocumentStatus.RUNNING.getCode())))
                .thenReturn(0);
        assertThat(documentStatusHelper.tryStartChunk("doc-1")).isFalse();
    }
}

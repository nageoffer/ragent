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

import com.nageoffer.ai.ragent.knowledge.dao.entity.FileCleanupTaskDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.VectorCleanupTaskDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.FileCleanupTaskMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.VectorCleanupTaskMapper;
import com.nageoffer.ai.ragent.knowledge.enums.CleanupTaskStatus;
import com.nageoffer.ai.ragent.knowledge.service.impl.CleanupTaskServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CleanupTaskServiceImplTest {

    @Mock
    private VectorCleanupTaskMapper vectorMapper;
    @Mock
    private FileCleanupTaskMapper fileMapper;
    @InjectMocks
    private CleanupTaskServiceImpl service;

    @Test
    void enqueueVectorCleanup_insertsPendingTask() {
        service.enqueueVectorCleanup("doc-1", "kb_collection");

        ArgumentCaptor<VectorCleanupTaskDO> captor = ArgumentCaptor.forClass(VectorCleanupTaskDO.class);
        verify(vectorMapper).insert(captor.capture());
        VectorCleanupTaskDO task = captor.getValue();
        assertThat(task.getDocId()).isEqualTo("doc-1");
        assertThat(task.getCollectionName()).isEqualTo("kb_collection");
        assertThat(task.getStatus()).isEqualTo(CleanupTaskStatus.PENDING.getCode());
        assertThat(task.getRetryCount()).isZero();
    }

    @Test
    void enqueueFileCleanup_skipsBlankUrl() {
        service.enqueueFileCleanup("  ");
        verify(fileMapper, org.mockito.Mockito.never())
                .insert(org.mockito.ArgumentMatchers.any(FileCleanupTaskDO.class));
    }

    @Test
    void enqueueFileCleanup_insertsPendingTask() {
        service.enqueueFileCleanup("s3://bucket/doc.pdf");

        ArgumentCaptor<FileCleanupTaskDO> captor = ArgumentCaptor.forClass(FileCleanupTaskDO.class);
        verify(fileMapper).insert(captor.capture());
        assertThat(captor.getValue().getFileUrl()).isEqualTo("s3://bucket/doc.pdf");
        assertThat(captor.getValue().getStatus()).isEqualTo(CleanupTaskStatus.PENDING.getCode());
    }
}

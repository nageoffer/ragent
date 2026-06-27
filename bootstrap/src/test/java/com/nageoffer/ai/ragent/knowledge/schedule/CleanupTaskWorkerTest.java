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

import com.nageoffer.ai.ragent.knowledge.config.CleanupTaskProperties;
import com.nageoffer.ai.ragent.knowledge.dao.entity.FileCleanupTaskDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.VectorCleanupTaskDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.FileCleanupTaskMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.VectorCleanupTaskMapper;
import com.nageoffer.ai.ragent.knowledge.enums.CleanupTaskStatus;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CleanupTaskWorkerTest {

    @Mock private VectorCleanupTaskMapper vectorMapper;
    @Mock private FileCleanupTaskMapper fileMapper;
    @Mock private VectorStoreService vectorStoreService;
    @Mock private FileStorageService fileStorageService;

    private CleanupTaskWorker worker;

    @BeforeEach
    void setUp() {
        CleanupTaskProperties props = new CleanupTaskProperties();
        props.setBatchSize(50);
        props.setMaxRetry(3);
        props.setBaseBackoffSeconds(30L);
        worker = new CleanupTaskWorker(vectorMapper, fileMapper, vectorStoreService, fileStorageService, props);
    }

    @Test
    void claimProcessingSql_rechecksRetryTimeWhenClaiming() {
        assertClaimProcessingRechecksRetryTime(VectorCleanupTaskMapper.class);
        assertClaimProcessingRechecksRetryTime(FileCleanupTaskMapper.class);
    }

    @Test
    void vectorTask_success_marksSuccess() {
        VectorCleanupTaskDO task = VectorCleanupTaskDO.builder()
                .id("t1").docId("doc-1").collectionName("kb").status("pending").retryCount(0).build();
        when(vectorMapper.selectList(any())).thenReturn(List.of(task));
        when(vectorMapper.claimProcessing(eq("t1"), anyString(), any(Date.class), any(Date.class), anyString(), anyString()))
                .thenReturn(1);
        when(vectorMapper.markSuccessIfOwned(eq("t1"), anyString(), anyString(), anyString())).thenReturn(1);
        when(fileMapper.selectList(any())).thenReturn(List.of());

        worker.scan();

        verify(vectorStoreService).deleteDocumentVectors("kb", "doc-1");
        ArgumentCaptor<String> claimOwner = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> successOwner = ArgumentCaptor.forClass(String.class);
        verify(vectorMapper).claimProcessing(eq("t1"), claimOwner.capture(), any(Date.class), any(Date.class),
                eq(CleanupTaskStatus.RUNNING.getCode()), eq(CleanupTaskStatus.PENDING.getCode()));
        verify(vectorMapper).markSuccessIfOwned(eq("t1"), successOwner.capture(),
                eq(CleanupTaskStatus.SUCCESS.getCode()), eq(CleanupTaskStatus.RUNNING.getCode()));
        assertThat(successOwner.getValue()).isEqualTo(claimOwner.getValue());
        assertThat(successOwner.getValue()).isNotBlank();
    }

    @Test
    void vectorTask_failure_incrementsRetryAndBackoff() {
        VectorCleanupTaskDO task = VectorCleanupTaskDO.builder()
                .id("t1").docId("doc-1").collectionName("kb").status("pending").retryCount(0).build();
        when(vectorMapper.selectList(any())).thenReturn(List.of(task));
        when(vectorMapper.claimProcessing(eq("t1"), anyString(), any(Date.class), any(Date.class), anyString(), anyString()))
                .thenReturn(1);
        when(vectorMapper.markRetryIfOwned(eq("t1"), anyString(), anyString(), anyString(),
                eq(1), any(Date.class), anyString())).thenReturn(1);
        when(fileMapper.selectList(any())).thenReturn(List.of());
        doThrow(new RuntimeException("pgvector down"))
                .when(vectorStoreService).deleteDocumentVectors(anyString(), anyString());

        worker.scan();

        verify(vectorMapper).markRetryIfOwned(eq("t1"), anyString(),
                eq(CleanupTaskStatus.PENDING.getCode()), eq(CleanupTaskStatus.RUNNING.getCode()),
                eq(1), any(Date.class), eq("pgvector down"));
    }

    @Test
    void vectorTask_exhaustedRetry_marksFailed() {
        VectorCleanupTaskDO task = VectorCleanupTaskDO.builder()
                .id("t1").docId("doc-1").collectionName("kb").status("pending").retryCount(3).build();
        when(vectorMapper.selectList(any())).thenReturn(List.of(task));
        when(vectorMapper.claimProcessing(eq("t1"), anyString(), any(Date.class), any(Date.class), anyString(), anyString()))
                .thenReturn(1);
        when(vectorMapper.markFailedIfOwned(eq("t1"), anyString(), anyString(), anyString(),
                eq(4), anyString())).thenReturn(1);
        when(fileMapper.selectList(any())).thenReturn(List.of());
        doThrow(new RuntimeException("still down"))
                .when(vectorStoreService).deleteDocumentVectors(anyString(), anyString());

        worker.scan();

        verify(vectorMapper).markFailedIfOwned(eq("t1"), anyString(),
                eq(CleanupTaskStatus.FAILED.getCode()), eq(CleanupTaskStatus.RUNNING.getCode()),
                eq(4), eq("still down"));
    }

    @Test
    void fileTask_success_marksSuccess() {
        FileCleanupTaskDO task = FileCleanupTaskDO.builder()
                .id("f1").fileUrl("s3://b/x.pdf").status("pending").retryCount(0).build();
        when(vectorMapper.selectList(any())).thenReturn(List.of());
        when(fileMapper.selectList(any())).thenReturn(List.of(task));
        when(fileMapper.claimProcessing(eq("f1"), anyString(), any(Date.class), any(Date.class), anyString(), anyString()))
                .thenReturn(1);
        when(fileMapper.markSuccessIfOwned(eq("f1"), anyString(), anyString(), anyString())).thenReturn(1);

        worker.scan();

        verify(fileStorageService).deleteByUrl("s3://b/x.pdf");
        ArgumentCaptor<String> claimOwner = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> successOwner = ArgumentCaptor.forClass(String.class);
        verify(fileMapper).claimProcessing(eq("f1"), claimOwner.capture(), any(Date.class), any(Date.class),
                eq(CleanupTaskStatus.RUNNING.getCode()), eq(CleanupTaskStatus.PENDING.getCode()));
        verify(fileMapper).markSuccessIfOwned(eq("f1"), successOwner.capture(),
                eq(CleanupTaskStatus.SUCCESS.getCode()), eq(CleanupTaskStatus.RUNNING.getCode()));
        assertThat(successOwner.getValue()).isEqualTo(claimOwner.getValue());
        assertThat(successOwner.getValue()).isNotBlank();
    }

    @Test
    void vectorTask_claimLost_skipsExternalDelete() {
        VectorCleanupTaskDO task = VectorCleanupTaskDO.builder()
                .id("t1").docId("doc-1").collectionName("kb").status("pending").retryCount(0).build();
        when(vectorMapper.selectList(any())).thenReturn(List.of(task));
        when(vectorMapper.claimProcessing(eq("t1"), anyString(), any(Date.class), any(Date.class), anyString(), anyString()))
                .thenReturn(0);
        when(fileMapper.selectList(any())).thenReturn(List.of());

        worker.scan();

        verify(vectorStoreService, never()).deleteDocumentVectors(anyString(), anyString());
    }

    @Test
    void fileTask_claimLost_skipsExternalDelete() {
        FileCleanupTaskDO task = FileCleanupTaskDO.builder()
                .id("f1").fileUrl("s3://b/x.pdf").status("pending").retryCount(0).build();
        when(vectorMapper.selectList(any())).thenReturn(List.of());
        when(fileMapper.selectList(any())).thenReturn(List.of(task));
        when(fileMapper.claimProcessing(eq("f1"), anyString(), any(Date.class), any(Date.class), anyString(), anyString()))
                .thenReturn(0);

        worker.scan();

        verify(fileStorageService, never()).deleteByUrl(anyString());
    }

    private void assertClaimProcessingRechecksRetryTime(Class<?> mapperType) {
        Method claimMethod = Arrays.stream(mapperType.getDeclaredMethods())
                .filter(method -> "claimProcessing".equals(method.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(claimMethod.getParameterCount()).isEqualTo(6);

        Update update = claimMethod.getAnnotation(Update.class);
        assertThat(update).isNotNull();
        String sql = String.join(" ", update.value()).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        assertThat(sql).contains("next_retry_time");
        assertThat(sql).contains("next_retry_time is null");
        assertThat(sql).contains("next_retry_time <= #{now}");
    }
}

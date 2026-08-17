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

import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleExecMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.enums.SourceType;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleRefreshProcessorTest {

    @TempDir
    Path tempDir;

    @Mock
    private KnowledgeDocumentScheduleMapper scheduleMapper;
    @Mock
    private KnowledgeDocumentScheduleExecMapper execMapper;
    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private KnowledgeBaseMapper kbMapper;
    @Mock
    private KnowledgeDocumentServiceImpl documentService;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private RemoteFileFetcher remoteFileFetcher;
    @Mock
    private ScheduleLockManager lockManager;
    @Mock
    private ScheduleStateManager stateManager;
    @Mock
    private DocumentStatusHelper documentStatusHelper;

    private ScheduleRefreshProcessor processor;
    private ScheduleLockLease lease;

    @BeforeEach
    void setUp() {
        processor = new ScheduleRefreshProcessor(
                scheduleMapper,
                execMapper,
                documentMapper,
                kbMapper,
                documentService,
                fileStorageService,
                remoteFileFetcher,
                lockManager,
                stateManager,
                documentStatusHelper
        );
        lease = new ScheduleLockLease("schedule-1", "lock-1");

        when(lockManager.startHeartbeat(lease)).thenAnswer(invocation -> newHeartbeat());
        when(lockManager.renew(lease)).thenReturn(true);
        when(lockManager.release(lease)).thenReturn(true);
    }

    @Test
    void shouldReleaseLeaseWhenHeartbeatCannotStart() {
        when(lockManager.startHeartbeat(lease)).thenThrow(new RuntimeException("heartbeat rejected"));

        processor.process(lease);

        verify(lockManager).release(lease);
        verifyNoInteractions(scheduleMapper, documentMapper, kbMapper, execMapper, remoteFileFetcher, documentService, documentStatusHelper, stateManager);
    }

    @Test
    void shouldMarkSkippedWhenRemoteFileUnchanged() {
        KnowledgeDocumentScheduleDO schedule = schedule();
        KnowledgeDocumentDO document = remoteDocument(DocumentStatus.SUCCESS.getCode(), "https://old-file");
        RemoteFileFetcher.RemoteFetchResult fetchResult = RemoteFileFetcher.RemoteFetchResult.skipped(
                "远程文件未变化",
                "etag-1",
                "last-modified-1",
                "hash-1"
        );

        when(scheduleMapper.selectById(lease.scheduleId())).thenReturn(schedule);
        when(documentMapper.selectById("doc-1")).thenReturn(document);
        mockExecInsert();
        when(remoteFileFetcher.fetchIfChanged(
                eq("https://example.com/file.pdf"),
                eq("etag-prev"),
                eq("last-modified-prev"),
                eq("hash-prev"),
                eq("doc.pdf")
        )).thenReturn(fetchResult);
        when(stateManager.markSkippedIfOwned(eq(lease), any(ScheduleStateContext.class), same(fetchResult))).thenReturn(true);

        processor.process(lease);

        verify(stateManager).markSkippedIfOwned(eq(lease), any(ScheduleStateContext.class), same(fetchResult));
        verifyNoInteractions(kbMapper, fileStorageService, documentService, documentStatusHelper);
        verify(lockManager).release(lease);
    }

    @Test
    void shouldDeleteUploadedFileWhenChunkDoesNotComplete() throws IOException {
        KnowledgeDocumentScheduleDO schedule = schedule();
        KnowledgeDocumentDO document = remoteDocument(DocumentStatus.SUCCESS.getCode(), "https://old-file");
        KnowledgeDocumentDO runtimeDoc = remoteDocument(DocumentStatus.RUNNING.getCode(), "https://old-file");
        KnowledgeDocumentDO latestDoc = remoteDocument(DocumentStatus.FAILED.getCode(), "https://old-file");
        KnowledgeBaseDO kb = knowledgeBase();
        StoredFileDTO stored = storedFile("https://new-file");
        RemoteFileFetcher.RemoteFetchResult fetchResult = changedFetchResult("hash-1", "etag-1", "last-modified-1");

        when(scheduleMapper.selectById(lease.scheduleId())).thenReturn(schedule);
        when(documentMapper.selectById("doc-1")).thenReturn(document, runtimeDoc, latestDoc);
        mockExecInsert();
        when(remoteFileFetcher.fetchIfChanged(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(fetchResult);
        when(documentStatusHelper.tryMarkRunning("doc-1")).thenReturn(true);
        when(kbMapper.selectById("kb-1")).thenReturn(kb);
        when(fileStorageService.upload(eq("kb-collection"), any(InputStream.class), anyLong(), eq("remote.pdf"), eq("application/pdf")))
                .thenReturn(stored);
        when(stateManager.markFailedIfOwned(eq(lease), any(ScheduleStateContext.class), eq("分块失败"))).thenReturn(true);

        processor.process(lease);

        verify(documentService).chunkDocument(any(KnowledgeDocumentDO.class));
        verify(stateManager).markFailedIfOwned(eq(lease), any(ScheduleStateContext.class), eq("分块失败"));
        verify(documentStatusHelper, never()).applyRefreshedFileMetadata(anyString(), any(StoredFileDTO.class));
        verify(documentStatusHelper, never()).markFailedIfRunning(anyString());
        verify(fileStorageService).deleteByUrl("https://new-file");
    }

    @Test
    void shouldRecoverRunningStatusWhenLeaseLostBeforeChunkStarts() throws IOException {
        KnowledgeDocumentScheduleDO schedule = schedule();
        KnowledgeDocumentDO document = remoteDocument(DocumentStatus.SUCCESS.getCode(), "https://old-file");
        KnowledgeDocumentDO runtimeDoc = remoteDocument(DocumentStatus.RUNNING.getCode(), "https://old-file");
        KnowledgeBaseDO kb = knowledgeBase();
        StoredFileDTO stored = storedFile("https://new-file");
        RemoteFileFetcher.RemoteFetchResult fetchResult = changedFetchResult("hash-1", "etag-1", "last-modified-1");

        when(lockManager.renew(lease)).thenReturn(true, true, false);
        when(scheduleMapper.selectById(lease.scheduleId())).thenReturn(schedule);
        when(documentMapper.selectById("doc-1")).thenReturn(document, runtimeDoc);
        mockExecInsert();
        when(remoteFileFetcher.fetchIfChanged(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(fetchResult);
        when(documentStatusHelper.tryMarkRunning("doc-1")).thenReturn(true);
        when(kbMapper.selectById("kb-1")).thenReturn(kb);
        when(fileStorageService.upload(eq("kb-collection"), any(InputStream.class), anyLong(), eq("remote.pdf"), eq("application/pdf")))
                .thenReturn(stored);

        processor.process(lease);

        verify(stateManager).markLeaseLost(any(ScheduleStateContext.class), eq("执行文档分块"));
        verify(documentStatusHelper).markFailedIfRunning("doc-1");
        verify(documentService, never()).chunkDocument(any(KnowledgeDocumentDO.class));
        verify(fileStorageService).deleteByUrl("https://new-file");
    }

    @Test
    void shouldMarkExecSuccessOnlyWhenStateWriteFailsAfterFileSwitch() throws IOException {
        KnowledgeDocumentScheduleDO schedule = schedule();
        KnowledgeDocumentDO document = remoteDocument(DocumentStatus.SUCCESS.getCode(), "https://old-file");
        KnowledgeDocumentDO runtimeDoc = remoteDocument(DocumentStatus.RUNNING.getCode(), "https://old-file");
        KnowledgeDocumentDO latestDoc = remoteDocument(DocumentStatus.SUCCESS.getCode(), "https://old-file");
        KnowledgeBaseDO kb = knowledgeBase();
        StoredFileDTO stored = storedFile("https://new-file");
        RemoteFileFetcher.RemoteFetchResult fetchResult = changedFetchResult("hash-1", "etag-1", "last-modified-1");

        when(scheduleMapper.selectById(lease.scheduleId())).thenReturn(schedule);
        when(documentMapper.selectById("doc-1")).thenReturn(document, runtimeDoc, latestDoc);
        mockExecInsert();
        when(remoteFileFetcher.fetchIfChanged(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(fetchResult);
        when(documentStatusHelper.tryMarkRunning("doc-1")).thenReturn(true);
        when(kbMapper.selectById("kb-1")).thenReturn(kb);
        when(fileStorageService.upload(eq("kb-collection"), any(InputStream.class), anyLong(), eq("remote.pdf"), eq("application/pdf")))
                .thenReturn(stored);
        doNothing().when(documentStatusHelper).applyRefreshedFileMetadata("doc-1", stored);
        when(stateManager.markSuccessIfOwned(eq(lease), any(ScheduleStateContext.class), same(fetchResult), same(stored)))
                .thenThrow(new RuntimeException("boom"));

        processor.process(lease);

        verify(documentStatusHelper).applyRefreshedFileMetadata("doc-1", stored);
        verify(stateManager).markSuccessExecOnly(
                any(ScheduleStateContext.class),
                same(stored),
                eq("hash-1"),
                eq("etag-1"),
                eq("last-modified-1"),
                eq("刷新成功（调度状态写回失败）")
        );
        verify(documentStatusHelper, never()).markFailedIfRunning(anyString());
        verify(fileStorageService).deleteByUrl("https://old-file");
    }

    private void mockExecInsert() {
        doAnswer(invocation -> {
            KnowledgeDocumentScheduleExecDO exec = invocation.getArgument(0);
            exec.setId("exec-1");
            return 1;
        }).when(execMapper).insert(any(KnowledgeDocumentScheduleExecDO.class));
    }

    private KnowledgeDocumentScheduleDO schedule() {
        return KnowledgeDocumentScheduleDO.builder()
                .id("schedule-1")
                .docId("doc-1")
                .cronExpr("0 0/5 * * * ?")
                .lastEtag("etag-prev")
                .lastModified("last-modified-prev")
                .lastContentHash("hash-prev")
                .build();
    }

    private KnowledgeDocumentDO remoteDocument(String status, String fileUrl) {
        return KnowledgeDocumentDO.builder()
                .id("doc-1")
                .kbId("kb-1")
                .docName("doc.pdf")
                .sourceType(SourceType.URL.getValue())
                .sourceLocation("https://example.com/file.pdf")
                .scheduleEnabled(1)
                .scheduleCron("0 0/5 * * * ?")
                .enabled(1)
                .deleted(0)
                .status(status)
                .fileUrl(fileUrl)
                .build();
    }

    private KnowledgeBaseDO knowledgeBase() {
        return KnowledgeBaseDO.builder()
                .id("kb-1")
                .collectionName("kb-collection")
                .build();
    }

    private StoredFileDTO storedFile(String url) {
        return StoredFileDTO.builder()
                .url(url)
                .detectedType("pdf")
                .size(128L)
                .originalFilename("remote.pdf")
                .build();
    }

    private RemoteFileFetcher.RemoteFetchResult changedFetchResult(String hash,
                                                                   String etag,
                                                                   String lastModified) throws IOException {
        Path tempFile = Files.createTempFile(tempDir, "schedule-refresh-", ".tmp");
        Files.writeString(tempFile, "updated document");
        return RemoteFileFetcher.RemoteFetchResult.changed(
                tempFile,
                Files.size(tempFile),
                "application/pdf",
                "remote.pdf",
                hash,
                etag,
                lastModified
        );
    }

    private ScheduleLockManager.ScheduleLockHeartbeat newHeartbeat() {
        try {
            Constructor<ScheduleLockManager.ScheduleLockHeartbeat> constructor =
                    ScheduleLockManager.ScheduleLockHeartbeat.class.getDeclaredConstructor(
                            ScheduleLockLease.class,
                            long.class,
                            long.class
                    );
            constructor.setAccessible(true);
            return constructor.newInstance(lease, System.currentTimeMillis(), 60_000L);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to create heartbeat for test", e);
        }
    }
}

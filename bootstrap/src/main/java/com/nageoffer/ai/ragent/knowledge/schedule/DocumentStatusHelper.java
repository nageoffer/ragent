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

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentStatusHelper {

    private static final String SYSTEM_USER = "system";

    private final KnowledgeDocumentMapper documentMapper;

    public String tryMarkRunning(String docId, String oldVersion) {
        String ownerVersion = nextVersion();
        return tryStartChunk(docId, oldVersion, ownerVersion, SYSTEM_USER)
                ? ownerVersion : null;
    }

    public boolean tryStartChunk(String docId, String oldVersion, String ownerVersion, String updatedBy) {
        return documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .set(KnowledgeDocumentDO::getDocumentVersion, ownerVersion)
                        .set(KnowledgeDocumentDO::getUpdatedBy, updatedBy)
                        .set(KnowledgeDocumentDO::getUpdateTime, new Date())
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
                        .eq(KnowledgeDocumentDO::getEnabled, 1)
                        .eq(KnowledgeDocumentDO::getDocumentVersion, oldVersion)
                        .in(KnowledgeDocumentDO::getStatus,
                                DocumentStatus.PENDING.getCode(),
                                DocumentStatus.FAILED.getCode(),
                                DocumentStatus.SUCCESS.getCode())) == 1;
    }

    public String tryMarkDeleting(String docId, String oldVersion, String updatedBy) {
        String ownerVersion = nextVersion();
        int updated = documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.DELETING.getCode())
                        .set(KnowledgeDocumentDO::getDocumentVersion, ownerVersion)
                        .set(KnowledgeDocumentDO::getUpdatedBy, updatedBy)
                        .set(KnowledgeDocumentDO::getUpdateTime, new Date())
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
                        .eq(KnowledgeDocumentDO::getDocumentVersion, oldVersion)
                        .in(KnowledgeDocumentDO::getStatus,
                                DocumentStatus.PENDING.getCode(),
                                DocumentStatus.FAILED.getCode(),
                                DocumentStatus.SUCCESS.getCode()));
        return updated == 1
                ? ownerVersion : null;
    }

    public boolean advanceStableVersion(String docId, String oldVersion, String newVersion, String updatedBy) {
        return documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getDocumentVersion, newVersion)
                        .set(KnowledgeDocumentDO::getUpdatedBy, updatedBy)
                        .set(KnowledgeDocumentDO::getUpdateTime, new Date())
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
                        .eq(KnowledgeDocumentDO::getDocumentVersion, oldVersion)
                        .in(KnowledgeDocumentDO::getStatus,
                                DocumentStatus.PENDING.getCode(),
                                DocumentStatus.FAILED.getCode(),
                                DocumentStatus.SUCCESS.getCode())) == 1;
    }

    public KnowledgeDocumentDO lockRunning(String docId, String ownerVersion) {
        // 状态检查后可能发生超时恢复并启动新一轮分块
        // 锁定当前版本直到所有 Sink 和成功状态一起提交，避免旧任务覆盖新任务
        KnowledgeDocumentDO document = documentMapper.selectRunningForUpdate(docId, ownerVersion);
        if (document == null) {
            throw new RuntimeException(
                    "文档操作版本已失效: docId=" + docId + ", documentVersion=" + ownerVersion);
        }
        return document;
    }

    public boolean markSucceeded(String docId, String ownerVersion, int chunkCount, String mimeType,
                                 StoredFileDTO refreshedFile, String updatedBy) {
        return documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.SUCCESS.getCode())
                        .set(KnowledgeDocumentDO::getChunkCount, chunkCount)
                        .set(KnowledgeDocumentDO::getMimeType, mimeType)
                        .set(refreshedFile != null && refreshedFile.getOriginalFilename() != null,
                                KnowledgeDocumentDO::getDocName,
                                refreshedFile != null ? refreshedFile.getOriginalFilename() : null)
                        .set(refreshedFile != null && refreshedFile.getUrl() != null,
                                KnowledgeDocumentDO::getFileUrl,
                                refreshedFile != null ? refreshedFile.getUrl() : null)
                        .set(refreshedFile != null && refreshedFile.getDetectedType() != null,
                                KnowledgeDocumentDO::getFileType,
                                refreshedFile != null ? refreshedFile.getDetectedType() : null)
                        .set(refreshedFile != null && refreshedFile.getSize() != null,
                                KnowledgeDocumentDO::getFileSize,
                                refreshedFile != null ? refreshedFile.getSize() : null)
                        .set(KnowledgeDocumentDO::getUpdatedBy, updatedBy)
                        .set(KnowledgeDocumentDO::getUpdateTime, new Date())
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .eq(KnowledgeDocumentDO::getDocumentVersion, ownerVersion)) == 1;
    }

    public boolean markFailedIfRunning(String docId, String ownerVersion) {
        return markFailedIfRunning(docId, ownerVersion, SYSTEM_USER);
    }

    public boolean markFailedIfRunning(String docId, String ownerVersion, String updatedBy) {
        return documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.FAILED.getCode())
                        .set(KnowledgeDocumentDO::getUpdatedBy, updatedBy)
                        .set(KnowledgeDocumentDO::getUpdateTime, new Date())
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .eq(KnowledgeDocumentDO::getDocumentVersion, ownerVersion)) == 1;
    }

    public StuckRecoveryResult recoverStuckRunning(long timeoutMinutes) {
        long safeTimeout = Math.max(timeoutMinutes, 10);
        Date threshold = new Date(System.currentTimeMillis() - safeTimeout * 60 * 1000);

        List<KnowledgeDocumentDO> stuck = documentMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .select(KnowledgeDocumentDO::getId, KnowledgeDocumentDO::getDocumentVersion)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
                        .eq(KnowledgeDocumentDO::getEnabled, 1)
                        .lt(KnowledgeDocumentDO::getUpdateTime, threshold)
        );

        int updated = 0;
        for (KnowledgeDocumentDO candidate : stuck) {
            updated += documentMapper.update(
                    Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                            .set(KnowledgeDocumentDO::getStatus, DocumentStatus.FAILED.getCode())
                            .set(KnowledgeDocumentDO::getDocumentVersion, nextVersion())
                            .set(KnowledgeDocumentDO::getUpdatedBy, SYSTEM_USER)
                            .set(KnowledgeDocumentDO::getUpdateTime, new Date())
                            .eq(KnowledgeDocumentDO::getId, candidate.getId())
                            .eq(KnowledgeDocumentDO::getDeleted, 0)
                            .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                            .eq(KnowledgeDocumentDO::getDocumentVersion, candidate.getDocumentVersion()));
        }
        if (updated != stuck.size()) {
            log.warn("卡死文档恢复时部分候选状态或版本已变化: 候选 {} 个, 实际重置 {} 个", stuck.size(), updated);
        }
        return new StuckRecoveryResult(stuck.stream().map(KnowledgeDocumentDO::getId).toList(), updated);
    }

    public String nextVersion() {
        return IdWorker.getIdStr();
    }

    public record StuckRecoveryResult(List<String> stuckDocIds, int actualRecovered) {
    }
}

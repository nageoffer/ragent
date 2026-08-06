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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
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
    private static final List<String> CHUNKABLE_STATUSES = List.of(
            DocumentStatus.PENDING.getCode(),
            DocumentStatus.FAILED.getCode(),
            DocumentStatus.SUCCESS.getCode()
    );
    private static final List<String> DELETABLE_STATUSES = List.of(
            DocumentStatus.PENDING.getCode(),
            DocumentStatus.FAILED.getCode(),
            DocumentStatus.SUCCESS.getCode()
    );

    private final KnowledgeDocumentMapper documentMapper;

    public boolean tryMarkRunning(String docId) {
        // Wrapper 更新不触发 updateTime 自动填充, 显式刷新, 使卡死恢复以分块开始时刻为基准
        return documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .set(KnowledgeDocumentDO::getUpdatedBy, SYSTEM_USER)
                        .set(KnowledgeDocumentDO::getUpdateTime, new Date())
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
                        .eq(KnowledgeDocumentDO::getEnabled, 1)
                        .in(KnowledgeDocumentDO::getStatus, CHUNKABLE_STATUSES)
        ) > 0;
    }

    public boolean tryStartChunk(String docId, String updatedBy) {
        return documentMapper.casStatus(
                docId,
                CHUNKABLE_STATUSES,
                DocumentStatus.RUNNING.getCode(),
                updatedBy
        ) > 0;
    }

    public boolean tryMarkDeleting(String docId, String updatedBy) {
        return documentMapper.casStatus(
                docId,
                DELETABLE_STATUSES,
                DocumentStatus.DELETING.getCode(),
                updatedBy
        ) > 0;
    }

    public boolean tryMarkSuccess(String docId, int chunkCount, String updatedBy) {
        return documentMapper.markSuccessIfRunning(
                docId,
                chunkCount,
                updatedBy,
                DocumentStatus.RUNNING.getCode(),
                DocumentStatus.SUCCESS.getCode()
        ) > 0;
    }

    public boolean tryMarkFailed(String docId, String updatedBy) {
        return documentMapper.casStatus(
                docId,
                List.of(DocumentStatus.RUNNING.getCode()),
                DocumentStatus.FAILED.getCode(),
                updatedBy
        ) > 0;
    }

    public void markFailedIfRunning(String docId) {
        tryMarkFailed(docId, SYSTEM_USER);
    }

    public void applyRefreshedFileMetadata(String docId, StoredFileDTO stored) {
        KnowledgeDocumentDO update = KnowledgeDocumentDO.builder()
                .id(docId)
                .docName(stored.getOriginalFilename())
                .fileUrl(stored.getUrl())
                .fileType(stored.getDetectedType())
                .fileSize(stored.getSize())
                .updatedBy(SYSTEM_USER)
                .build();
        int updated = documentMapper.updateById(update);
        if (updated == 0) {
            throw new ClientException("文档不存在");
        }
    }

    public StuckRecoveryResult recoverStuckRunning(long timeoutMinutes) {
        long safeTimeout = Math.max(timeoutMinutes, 10);
        Date threshold = new Date(System.currentTimeMillis() - safeTimeout * 60 * 1000);

        List<String> stuckDocIds = documentMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .select(KnowledgeDocumentDO::getId)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .eq(KnowledgeDocumentDO::getEnabled, 1)
                        .lt(KnowledgeDocumentDO::getUpdateTime, threshold)
        ).stream().map(KnowledgeDocumentDO::getId).toList();

        if (stuckDocIds.isEmpty()) {
            return new StuckRecoveryResult(List.of(), 0);
        }

        int updated = documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.FAILED.getCode())
                        .set(KnowledgeDocumentDO::getUpdatedBy, SYSTEM_USER)
                        .in(KnowledgeDocumentDO::getId, stuckDocIds)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
        );

        if (updated != stuckDocIds.size()) {
            log.warn("卡死文档恢复时部分候选状态已变化: 候选 {} 个, 实际重置 {} 个",
                    stuckDocIds.size(), updated);
        }

        return new StuckRecoveryResult(stuckDocIds, updated);
    }

    public record StuckRecoveryResult(List<String> stuckDocIds, int actualRecovered) {
    }
}

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

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleExecMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import com.nageoffer.ai.ragent.knowledge.enums.ScheduleRunStatus;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Date;

@Component
@RequiredArgsConstructor
public class ScheduleStateManager {

    private static final String LEASE_LOST_NOTE = "（调度锁已失效，未写回调度状态）";

    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    private final KnowledgeDocumentScheduleExecMapper execMapper;

    public boolean markSkippedIfOwned(ScheduleLockLease lease,
                                      ScheduleStateContext ctx,
                                      RemoteFileFetcher.RemoteFetchResult fetchResult) {
        boolean scheduleUpdated = updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getCronExpr, ctx.getCronExpr())
                        .set(KnowledgeDocumentScheduleDO::getLastRunTime, ctx.getStartTime())
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, ctx.getNextRunTime())
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.SKIPPED.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, fetchResult.message())
                        .set(KnowledgeDocumentScheduleDO::getLastEtag, fetchResult.etag())
                        .set(KnowledgeDocumentScheduleDO::getLastModified, fetchResult.lastModified())
                        .set(KnowledgeDocumentScheduleDO::getLastContentHash, fetchResult.contentHash())
        );

        if (ctx.getExecId() != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = new KnowledgeDocumentScheduleExecDO();
            execUpdate.setId(ctx.getExecId());
            execUpdate.setStatus(ScheduleRunStatus.SKIPPED.getCode());
            execUpdate.setMessage(withLeaseNote(fetchResult.message(), scheduleUpdated));
            execUpdate.setEndTime(new Date());
            execUpdate.setContentHash(fetchResult.contentHash());
            execUpdate.setEtag(fetchResult.etag());
            execUpdate.setLastModified(fetchResult.lastModified());
            execMapper.updateById(execUpdate);
        }
        return scheduleUpdated;
    }

    public boolean markSkippedIfOwned(ScheduleLockLease lease, ScheduleStateContext ctx, String message) {
        boolean scheduleUpdated = updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getCronExpr, ctx.getCronExpr())
                        .set(KnowledgeDocumentScheduleDO::getLastRunTime, ctx.getStartTime())
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, ctx.getNextRunTime())
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.SKIPPED.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, message)
        );

        if (ctx.getExecId() != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = KnowledgeDocumentScheduleExecDO.builder()
                    .id(ctx.getExecId())
                    .status(ScheduleRunStatus.SKIPPED.getCode())
                    .message(withLeaseNote(message, scheduleUpdated))
                    .endTime(new Date())
                    .build();
            execMapper.updateById(execUpdate);
        }
        return scheduleUpdated;
    }

    public boolean markSuccessIfOwned(ScheduleLockLease lease,
                                      ScheduleStateContext ctx,
                                      RemoteFileFetcher.RemoteFetchResult fetchResult,
                                      StoredFileDTO stored) {
        Date endTime = new Date();
        boolean scheduleUpdated = updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getCronExpr, ctx.getCronExpr())
                        .set(KnowledgeDocumentScheduleDO::getLastRunTime, ctx.getStartTime())
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, ctx.getNextRunTime())
                        .set(KnowledgeDocumentScheduleDO::getLastSuccessTime, endTime)
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.SUCCESS.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, null)
                        .set(KnowledgeDocumentScheduleDO::getLastEtag, fetchResult.etag())
                        .set(KnowledgeDocumentScheduleDO::getLastModified, fetchResult.lastModified())
                        .set(KnowledgeDocumentScheduleDO::getLastContentHash, fetchResult.contentHash())
        );

        if (ctx.getExecId() != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = KnowledgeDocumentScheduleExecDO.builder()
                    .id(ctx.getExecId())
                    .status(ScheduleRunStatus.SUCCESS.getCode())
                    .message(withLeaseNote("刷新成功", scheduleUpdated))
                    .endTime(endTime)
                    .fileName(stored.getOriginalFilename())
                    .fileSize(stored.getSize())
                    .contentHash(fetchResult.contentHash())
                    .etag(fetchResult.etag())
                    .lastModified(fetchResult.lastModified())
                    .build();
            execMapper.updateById(execUpdate);
        }
        return scheduleUpdated;
    }

    public boolean markFailedIfOwned(ScheduleLockLease lease, ScheduleStateContext ctx, String errorMessage) {
        String truncatedErrorMessage = truncate(errorMessage);
        boolean scheduleUpdated = updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getCronExpr, ctx.getCronExpr())
                        .set(KnowledgeDocumentScheduleDO::getLastRunTime, ctx.getStartTime())
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, ctx.getNextRunTime())
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.FAILED.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, truncatedErrorMessage)
        );

        if (ctx.getExecId() != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = new KnowledgeDocumentScheduleExecDO();
            execUpdate.setId(ctx.getExecId());
            execUpdate.setStatus(ScheduleRunStatus.FAILED.getCode());
            execUpdate.setMessage(withLeaseNote(truncatedErrorMessage, scheduleUpdated));
            execUpdate.setEndTime(new Date());
            execMapper.updateById(execUpdate);
        }
        return scheduleUpdated;
    }

    public boolean disableIfOwned(ScheduleLockLease lease, String reason) {
        return updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getEnabled, 0)
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, null)
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.FAILED.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, truncate(reason))
        );
    }

    public void markLeaseLost(ScheduleStateContext ctx, String stage) {
        if (ctx == null || ctx.getExecId() == null) {
            return;
        }
        String message = "调度锁已失效，终止执行";
        if (StringUtils.hasText(stage)) {
            message += ": " + stage;
        }
        KnowledgeDocumentScheduleExecDO execUpdate = new KnowledgeDocumentScheduleExecDO();
        execUpdate.setId(ctx.getExecId());
        execUpdate.setStatus(ScheduleRunStatus.FAILED.getCode());
        execUpdate.setMessage(truncate(message));
        execUpdate.setEndTime(new Date());
        execMapper.updateById(execUpdate);
    }

    public void markSuccessExecOnly(ScheduleStateContext ctx,
                                    StoredFileDTO stored,
                                    String contentHash,
                                    String etag,
                                    String lastModified,
                                    String message) {
        if (ctx == null || ctx.getExecId() == null) {
            return;
        }
        KnowledgeDocumentScheduleExecDO execUpdate = KnowledgeDocumentScheduleExecDO.builder()
                .id(ctx.getExecId())
                .status(ScheduleRunStatus.SUCCESS.getCode())
                .message(truncate(message))
                .endTime(new Date())
                .fileName(stored != null ? stored.getOriginalFilename() : null)
                .fileSize(stored != null ? stored.getSize() : null)
                .contentHash(contentHash)
                .etag(etag)
                .lastModified(lastModified)
                .build();
        execMapper.updateById(execUpdate);
    }

    private boolean updateScheduleIfOwned(ScheduleLockLease lease,
                                          LambdaUpdateWrapper<KnowledgeDocumentScheduleDO> updateWrapper) {
        if (lease == null || updateWrapper == null) {
            return false;
        }
        updateWrapper.eq(KnowledgeDocumentScheduleDO::getId, lease.scheduleId())
                .eq(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken());
        return scheduleMapper.update(updateWrapper) > 0;
    }

    private String withLeaseNote(String message, boolean scheduleUpdated) {
        if (scheduleUpdated) {
            return truncate(message);
        }
        String baseMessage = StringUtils.hasText(message) ? message.trim() : "执行完成";
        return truncate(baseMessage + LEASE_LOST_NOTE);
    }

    private String truncate(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 512) {
            return trimmed;
        }
        return trimmed.substring(0, 512);
    }
}

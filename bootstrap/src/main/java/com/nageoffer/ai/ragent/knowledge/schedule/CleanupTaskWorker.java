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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.knowledge.config.CleanupTaskProperties;
import com.nageoffer.ai.ragent.knowledge.dao.entity.FileCleanupTaskDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.VectorCleanupTaskDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.FileCleanupTaskMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.VectorCleanupTaskMapper;
import com.nageoffer.ai.ragent.knowledge.enums.CleanupTaskStatus;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * outbox 清理任务 worker：异步执行 PgVector / 文件存储删除，失败退避重试，超阈值告警。
 * 任务领取后所有状态写回均带 lockOwner，避免过期 worker 覆盖新 owner 的结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CleanupTaskWorker {

    private final VectorCleanupTaskMapper vectorMapper;
    private final FileCleanupTaskMapper fileMapper;
    private final VectorStoreService vectorStoreService;
    private final FileStorageService fileStorageService;
    private final CleanupTaskProperties properties;

    @Scheduled(fixedDelayString = "${rag.knowledge.cleanup.scan-delay-ms:15000}")
    public void scan() {
        recoverExpiredProcessingTasks();
        scanVectorTasks();
        scanFileTasks();
    }

    private void recoverExpiredProcessingTasks() {
        Date now = new Date();
        vectorMapper.recoverExpiredProcessing(now, CleanupTaskStatus.RUNNING_CODE, CleanupTaskStatus.PENDING_CODE);
        fileMapper.recoverExpiredProcessing(now, CleanupTaskStatus.RUNNING_CODE, CleanupTaskStatus.PENDING_CODE);
    }

    private void scanVectorTasks() {
        Date now = new Date();
        LambdaQueryWrapper<VectorCleanupTaskDO> qw = Wrappers.lambdaQuery(VectorCleanupTaskDO.class)
                .eq(VectorCleanupTaskDO::getStatus, CleanupTaskStatus.PENDING.getCode())
                .and(w -> w.isNull(VectorCleanupTaskDO::getNextRetryTime)
                        .or().le(VectorCleanupTaskDO::getNextRetryTime, now))
                .last("LIMIT " + Math.max(properties.getBatchSize(), 1));
        List<VectorCleanupTaskDO> tasks = vectorMapper.selectList(qw);
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        for (VectorCleanupTaskDO task : tasks) {
            String lockOwner = nextLockOwner();
            if (vectorMapper.claimProcessing(task.getId(), lockOwner, computeProcessingLeaseUntil(), now,
                    CleanupTaskStatus.RUNNING_CODE, CleanupTaskStatus.PENDING_CODE) == 0) {
                continue;
            }
            try {
                vectorStoreService.deleteDocumentVectors(task.getCollectionName(), task.getDocId());
                markVectorSuccess(task, lockOwner);
            } catch (Exception e) {
                handleVectorFailure(task, lockOwner, e);
            }
        }
    }

    private void markVectorSuccess(VectorCleanupTaskDO task, String lockOwner) {
        int updated = vectorMapper.markSuccessIfOwned(
                task.getId(),
                lockOwner,
                CleanupTaskStatus.SUCCESS.getCode(),
                CleanupTaskStatus.RUNNING.getCode()
        );
        if (updated == 0) {
            log.warn("向量清理任务完成但锁已失效，跳过状态写回: taskId={}, docId={}", task.getId(), task.getDocId());
        }
    }

    private void handleVectorFailure(VectorCleanupTaskDO task, String lockOwner, Exception e) {
        int nextRetry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        int updated;
        if (nextRetry > properties.getMaxRetry()) {
            updated = vectorMapper.markFailedIfOwned(
                    task.getId(),
                    lockOwner,
                    CleanupTaskStatus.FAILED.getCode(),
                    CleanupTaskStatus.RUNNING.getCode(),
                    nextRetry,
                    truncate(e.getMessage())
            );
            log.error("向量清理任务重试耗尽，转入死信需人工介入: taskId={}, docId={}, collection={}",
                    task.getId(), task.getDocId(), task.getCollectionName(), e);
        } else {
            updated = vectorMapper.markRetryIfOwned(
                    task.getId(),
                    lockOwner,
                    CleanupTaskStatus.PENDING.getCode(),
                    CleanupTaskStatus.RUNNING.getCode(),
                    nextRetry,
                    computeNextRetry(nextRetry),
                    truncate(e.getMessage())
            );
            log.warn("向量清理任务失败，第 {} 次重试: taskId={}, docId={}", nextRetry, task.getId(), task.getDocId(), e);
        }
        if (updated == 0) {
            log.warn("向量清理任务失败但锁已失效，跳过状态写回: taskId={}, docId={}", task.getId(), task.getDocId());
        }
    }

    private void scanFileTasks() {
        Date now = new Date();
        LambdaQueryWrapper<FileCleanupTaskDO> qw = Wrappers.lambdaQuery(FileCleanupTaskDO.class)
                .eq(FileCleanupTaskDO::getStatus, CleanupTaskStatus.PENDING.getCode())
                .and(w -> w.isNull(FileCleanupTaskDO::getNextRetryTime)
                        .or().le(FileCleanupTaskDO::getNextRetryTime, now))
                .last("LIMIT " + Math.max(properties.getBatchSize(), 1));
        List<FileCleanupTaskDO> tasks = fileMapper.selectList(qw);
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        for (FileCleanupTaskDO task : tasks) {
            String lockOwner = nextLockOwner();
            if (fileMapper.claimProcessing(task.getId(), lockOwner, computeProcessingLeaseUntil(), now,
                    CleanupTaskStatus.RUNNING_CODE, CleanupTaskStatus.PENDING_CODE) == 0) {
                continue;
            }
            try {
                fileStorageService.deleteByUrl(task.getFileUrl());
                int updated = fileMapper.markSuccessIfOwned(
                        task.getId(),
                        lockOwner,
                        CleanupTaskStatus.SUCCESS.getCode(),
                        CleanupTaskStatus.RUNNING.getCode()
                );
                if (updated == 0) {
                    log.warn("文件清理任务完成但锁已失效，跳过状态写回: taskId={}, fileUrl={}",
                            task.getId(), task.getFileUrl());
                }
            } catch (Exception e) {
                handleFileFailure(task, lockOwner, e);
            }
        }
    }

    private void handleFileFailure(FileCleanupTaskDO task, String lockOwner, Exception e) {
        int nextRetry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        int updated;
        if (nextRetry > properties.getMaxRetry()) {
            updated = fileMapper.markFailedIfOwned(
                    task.getId(),
                    lockOwner,
                    CleanupTaskStatus.FAILED.getCode(),
                    CleanupTaskStatus.RUNNING.getCode(),
                    nextRetry,
                    truncate(e.getMessage())
            );
            log.error("文件清理任务重试耗尽，转入死信需人工介入: taskId={}, fileUrl={}",
                    task.getId(), task.getFileUrl(), e);
        } else {
            updated = fileMapper.markRetryIfOwned(
                    task.getId(),
                    lockOwner,
                    CleanupTaskStatus.PENDING.getCode(),
                    CleanupTaskStatus.RUNNING.getCode(),
                    nextRetry,
                    computeNextRetry(nextRetry),
                    truncate(e.getMessage())
            );
            log.warn("文件清理任务失败，第 {} 次重试: taskId={}, fileUrl={}", nextRetry, task.getId(), task.getFileUrl(), e);
        }
        if (updated == 0) {
            log.warn("文件清理任务失败但锁已失效，跳过状态写回: taskId={}, fileUrl={}", task.getId(), task.getFileUrl());
        }
    }

    private Date computeNextRetry(int retryCount) {
        long backoffSeconds = properties.getBaseBackoffSeconds() * (1L << Math.min(retryCount, 10));
        return new Date(System.currentTimeMillis() + backoffSeconds * 1000L);
    }

    private Date computeProcessingLeaseUntil() {
        long leaseMillis = Math.max(properties.getScanDelayMs() * 4, 60_000L);
        return new Date(System.currentTimeMillis() + leaseMillis);
    }

    private String nextLockOwner() {
        return "cleanup-" + UUID.randomUUID();
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}

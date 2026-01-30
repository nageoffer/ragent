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
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleExecMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.enums.ScheduleRunStatus;
import com.nageoffer.ai.ragent.knowledge.enums.SourceType;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.ingestion.util.HttpClientHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 知识库文档定时刷新任务
 * <p>
 * 该组件负责定期扫描和执行知识库文档的自动刷新任务，主要用于处理URL类型的文档源
 * 支持基于Cron表达式的定时调度，并提供分布式锁机制以确保任务不会重复执行
 * </p>
 *
 * <p>主要功能：</p>
 * <ul>
 *   <li>定期扫描待执行的定时任务</li>
 *   <li>检查远程文件是否发生变化（基于ETag、Last-Modified、内容哈希）</li>
 *   <li>下载更新的远程文件并重新进行文档分块处理</li>
 *   <li>记录任务执行历史和状态</li>
 *   <li>支持分布式环境下的任务锁定机制</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentScheduleJob {

    private static final String SYSTEM_USER = "system";

    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    private final KnowledgeDocumentScheduleExecMapper execMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeChunkService knowledgeChunkService;
    private final KnowledgeDocumentServiceImpl documentService;
    private final FileStorageService fileStorageService;
    private final HttpClientHelper httpClientHelper;
    private final PlatformTransactionManager transactionManager;
    @Qualifier("knowledgeChunkExecutor")
    private final Executor knowledgeChunkExecutor;

    @Value("${rag.knowledge.schedule.lock-seconds:900}")
    private long lockSeconds;
    @Value("${rag.knowledge.schedule.batch-size:20}")
    private int batchSize;
    @Value("${rag.knowledge.schedule.max-file-size-bytes:104857600}")
    private long maxFileSizeBytes;

    private final String instanceId = resolveInstanceId();

    @Scheduled(fixedDelayString = "${rag.knowledge.schedule.scan-delay-ms:10000}")
    public void scan() {
        Date now = new Date();
        List<KnowledgeDocumentScheduleDO> schedules = scheduleMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentScheduleDO>()
                        .eq(KnowledgeDocumentScheduleDO::getEnabled, 1)
                        .and(wrapper -> wrapper.isNull(KnowledgeDocumentScheduleDO::getNextRunTime)
                                .or()
                                .le(KnowledgeDocumentScheduleDO::getNextRunTime, now))
                        .and(wrapper -> wrapper.isNull(KnowledgeDocumentScheduleDO::getLockUntil)
                                .or()
                                .lt(KnowledgeDocumentScheduleDO::getLockUntil, now))
                        .orderByAsc(KnowledgeDocumentScheduleDO::getNextRunTime)
                        .last("LIMIT " + Math.max(batchSize, 1))
        );

        if (schedules == null || schedules.isEmpty()) {
            return;
        }

        Date lockUntil = new Date(System.currentTimeMillis() + Math.max(lockSeconds, 60) * 1000);
        for (KnowledgeDocumentScheduleDO schedule : schedules) {
            if (schedule == null || schedule.getId() == null) {
                continue;
            }
            if (!tryAcquireLock(schedule.getId(), now, lockUntil)) {
                continue;
            }
            try {
                knowledgeChunkExecutor.execute(() -> executeSchedule(schedule.getId()));
            } catch (RejectedExecutionException e) {
                log.error("定时任务提交失败: scheduleId={}, docId={}, kbId={}",
                        schedule.getId(), schedule.getDocId(), schedule.getKbId(), e);
                releaseLock(schedule.getId());
            }
        }
    }

    private void executeSchedule(Long scheduleId) {
        Date startTime = new Date();
        KnowledgeDocumentScheduleDO schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            return;
        }
        renewLock(scheduleId);

        KnowledgeDocumentDO document = documentMapper.selectById(schedule.getDocId());
        if (document == null || (document.getDeleted() != null && document.getDeleted() == 1)) {
            disableSchedule(schedule, "文档不存在或已删除");
            releaseLock(scheduleId);
            return;
        }
        if (document.getEnabled() != null && document.getEnabled() == 0) {
            disableSchedule(schedule, "文档已禁用");
            releaseLock(scheduleId);
            return;
        }

        String cron = document.getScheduleCron();
        boolean enabled = document.getScheduleEnabled() != null && document.getScheduleEnabled() == 1;
        if (!StringUtils.hasText(cron) || !SourceType.URL.getValue().equalsIgnoreCase(document.getSourceType())) {
            enabled = false;
        }

        schedule.setCronExpr(cron);
        Date nextRunTime;
        if (enabled) {
            try {
                nextRunTime = CronScheduleHelper.nextRunTime(cron, startTime);
            } catch (IllegalArgumentException e) {
                disableSchedule(schedule, "定时表达式不合法");
                releaseLock(scheduleId);
                return;
            }
            if (nextRunTime == null) {
                disableSchedule(schedule, "无法计算下次执行时间");
                releaseLock(scheduleId);
                return;
            }
        } else {
            disableSchedule(schedule, "定时已关闭");
            releaseLock(scheduleId);
            return;
        }

        KnowledgeDocumentScheduleExecDO exec = KnowledgeDocumentScheduleExecDO.builder()
                .scheduleId(scheduleId)
                .docId(document.getId())
                .kbId(document.getKbId())
                .status(ScheduleRunStatus.RUNNING.getCode())
                .startTime(startTime)
                .build();
        execMapper.insert(exec);

        try {
            RemoteFetchResult fetchResult = fetchRemoteIfChanged(document, schedule);
            if (!fetchResult.changed()) {
                markScheduleSkipped(schedule, exec.getId(), startTime, nextRunTime, fetchResult);
                return;
            }
            renewLock(scheduleId);

            KnowledgeBaseDO kbDO = kbMapper.selectById(document.getKbId());
            if (kbDO == null) {
                throw new ClientException("知识库不存在");
            }

            StoredFileDTO stored = fileStorageService.upload(
                    kbDO.getCollectionName(),
                    fetchResult.body(),
                    fetchResult.fileName(),
                    fetchResult.contentType()
            );

            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            txTemplate.executeWithoutResult(status -> {
                knowledgeChunkService.deleteByDocId(String.valueOf(document.getId()));
                KnowledgeDocumentDO update = new KnowledgeDocumentDO();
                update.setId(document.getId());
                update.setDocName(stored.getOriginalFilename());
                update.setFileUrl(stored.getUrl());
                update.setFileType(stored.getDetectedType());
                update.setFileSize(stored.getSize());
                update.setChunkCount(0);
                update.setStatus(DocumentStatus.RUNNING.getCode());
                update.setUpdatedBy(SYSTEM_USER);
                documentMapper.updateById(update);
            });

            document.setDocName(stored.getOriginalFilename());
            document.setFileUrl(stored.getUrl());
            document.setFileType(stored.getDetectedType());
            document.setFileSize(stored.getSize());
            document.setChunkCount(0);
            document.setStatus(DocumentStatus.RUNNING.getCode());

            renewLock(scheduleId);
            UserContext.set(LoginUser.builder().username(SYSTEM_USER).build());
            try {
                documentService.chunkDocument(document);
            } finally {
                UserContext.clear();
            }

            KnowledgeDocumentDO latest = documentMapper.selectById(document.getId());
            if (latest == null || !DocumentStatus.SUCCESS.getCode().equals(latest.getStatus())) {
                markScheduleFailed(schedule, exec.getId(), startTime, nextRunTime, "分块失败");
                return;
            }

            markScheduleSuccess(schedule, exec.getId(), startTime, nextRunTime, fetchResult, stored);
        } catch (Exception e) {
            log.error("定时刷新失败: scheduleId={}, docId={}, kbId={}",
                    scheduleId, document.getId(), document.getKbId(), e);
            markScheduleFailed(schedule, exec.getId(), startTime, nextRunTime, e.getMessage());
        } finally {
            releaseLock(scheduleId);
        }
    }

    private RemoteFetchResult fetchRemoteIfChanged(KnowledgeDocumentDO document, KnowledgeDocumentScheduleDO schedule) {
        String url = document.getSourceLocation();
        if (!StringUtils.hasText(url)) {
            throw new ClientException("文档来源地址为空");
        }
        url = url.trim();

        HttpClientHelper.HttpHeadResponse headResponse = null;
        try {
            headResponse = httpClientHelper.head(url, Map.of());
        } catch (Exception e) {
            log.debug("HEAD 获取失败，尝试直接下载: {}", url, e);
        }

        if (headResponse != null) {
            if (maxFileSizeBytes > 0 && headResponse.contentLength() != null && headResponse.contentLength() > maxFileSizeBytes) {
                throw new ClientException("远程文件大小超过限制: " + maxFileSizeBytes + " bytes");
            }
            String etag = trim(headResponse.etag());
            String lastModified = trim(headResponse.lastModified());
            boolean etagMatch = StringUtils.hasText(etag) && etag.equals(trim(schedule.getLastEtag()));
            boolean modifiedMatch = StringUtils.hasText(lastModified) && lastModified.equals(trim(schedule.getLastModified()));
            if (etagMatch || modifiedMatch) {
                return RemoteFetchResult.skipped("远程文件未变化", etag, lastModified, schedule.getLastContentHash());
            }
        }

        HttpClientHelper.HttpFetchResponse fetchResponse = maxFileSizeBytes > 0
                ? httpClientHelper.getWithLimit(url, Map.of(), maxFileSizeBytes)
                : httpClientHelper.get(url, Map.of());
        byte[] body = fetchResponse.body() == null ? new byte[0] : fetchResponse.body();
        if (body.length == 0) {
            throw new ClientException("远程文件内容为空");
        }
        String hash = sha256Hex(body);
        if (StringUtils.hasText(hash) && hash.equals(trim(schedule.getLastContentHash()))) {
            String etag = StringUtils.hasText(fetchResponse.etag())
                    ? trim(fetchResponse.etag())
                    : (headResponse == null ? null : trim(headResponse.etag()));
            String lastModified = StringUtils.hasText(fetchResponse.lastModified())
                    ? trim(fetchResponse.lastModified())
                    : (headResponse == null ? null : trim(headResponse.lastModified()));
            return RemoteFetchResult.skipped("内容哈希未变化", etag, lastModified, hash);
        }

        String fileName = StringUtils.hasText(fetchResponse.fileName())
                ? fetchResponse.fileName()
                : document.getDocName();
        String etag = StringUtils.hasText(fetchResponse.etag())
                ? trim(fetchResponse.etag())
                : (headResponse == null ? null : trim(headResponse.etag()));
        String lastModified = StringUtils.hasText(fetchResponse.lastModified())
                ? trim(fetchResponse.lastModified())
                : (headResponse == null ? null : trim(headResponse.lastModified()));
        return RemoteFetchResult.changed(
                body,
                fetchResponse.contentType(),
                fileName,
                hash,
                etag,
                lastModified
        );
    }

    private void markScheduleSkipped(KnowledgeDocumentScheduleDO schedule,
                                     Long execId,
                                     Date startTime,
                                     Date nextRunTime,
                                     RemoteFetchResult fetchResult) {
        Date endTime = new Date();
        KnowledgeDocumentScheduleDO update = new KnowledgeDocumentScheduleDO();
        update.setId(schedule.getId());
        update.setCronExpr(schedule.getCronExpr());
        update.setLastRunTime(startTime);
        update.setNextRunTime(nextRunTime);
        update.setLastStatus(ScheduleRunStatus.SKIPPED.getCode());
        update.setLastError(fetchResult.message());
        update.setLastEtag(fetchResult.etag());
        update.setLastModified(fetchResult.lastModified());
        update.setLastContentHash(fetchResult.contentHash());
        scheduleMapper.updateById(update);

        if (execId != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = new KnowledgeDocumentScheduleExecDO();
            execUpdate.setId(execId);
            execUpdate.setStatus(ScheduleRunStatus.SKIPPED.getCode());
            execUpdate.setMessage(fetchResult.message());
            execUpdate.setEndTime(endTime);
            execUpdate.setContentHash(fetchResult.contentHash());
            execUpdate.setEtag(fetchResult.etag());
            execUpdate.setLastModified(fetchResult.lastModified());
            execMapper.updateById(execUpdate);
        }
    }

    private void markScheduleSuccess(KnowledgeDocumentScheduleDO schedule,
                                     Long execId,
                                     Date startTime,
                                     Date nextRunTime,
                                     RemoteFetchResult fetchResult,
                                     StoredFileDTO stored) {
        Date endTime = new Date();
        KnowledgeDocumentScheduleDO update = new KnowledgeDocumentScheduleDO();
        update.setId(schedule.getId());
        update.setCronExpr(schedule.getCronExpr());
        update.setLastRunTime(startTime);
        update.setNextRunTime(nextRunTime);
        update.setLastSuccessTime(endTime);
        update.setLastStatus(ScheduleRunStatus.SUCCESS.getCode());
        update.setLastError(null);
        update.setLastEtag(fetchResult.etag());
        update.setLastModified(fetchResult.lastModified());
        update.setLastContentHash(fetchResult.contentHash());
        scheduleMapper.updateById(update);

        if (execId != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = new KnowledgeDocumentScheduleExecDO();
            execUpdate.setId(execId);
            execUpdate.setStatus(ScheduleRunStatus.SUCCESS.getCode());
            execUpdate.setMessage("刷新成功");
            execUpdate.setEndTime(endTime);
            execUpdate.setFileName(stored.getOriginalFilename());
            execUpdate.setFileSize(stored.getSize());
            execUpdate.setContentHash(fetchResult.contentHash());
            execUpdate.setEtag(fetchResult.etag());
            execUpdate.setLastModified(fetchResult.lastModified());
            execMapper.updateById(execUpdate);
        }
    }

    private void markScheduleFailed(KnowledgeDocumentScheduleDO schedule,
                                    Long execId,
                                    Date startTime,
                                    Date nextRunTime,
                                    String errorMessage) {
        Date endTime = new Date();
        KnowledgeDocumentScheduleDO update = new KnowledgeDocumentScheduleDO();
        update.setId(schedule.getId());
        update.setCronExpr(schedule.getCronExpr());
        update.setLastRunTime(startTime);
        update.setNextRunTime(nextRunTime);
        update.setLastStatus(ScheduleRunStatus.FAILED.getCode());
        update.setLastError(truncate(errorMessage));
        scheduleMapper.updateById(update);

        if (execId != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = new KnowledgeDocumentScheduleExecDO();
            execUpdate.setId(execId);
            execUpdate.setStatus(ScheduleRunStatus.FAILED.getCode());
            execUpdate.setMessage(truncate(errorMessage));
            execUpdate.setEndTime(endTime);
            execMapper.updateById(execUpdate);
        }
    }

    private void disableSchedule(KnowledgeDocumentScheduleDO schedule, String reason) {
        KnowledgeDocumentScheduleDO update = new KnowledgeDocumentScheduleDO();
        update.setId(schedule.getId());
        update.setEnabled(0);
        update.setNextRunTime(null);
        update.setLastStatus(ScheduleRunStatus.FAILED.getCode());
        update.setLastError(truncate(reason));
        scheduleMapper.updateById(update);
    }

    private boolean tryAcquireLock(Long scheduleId, Date now, Date lockUntil) {
        UpdateWrapper<KnowledgeDocumentScheduleDO> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", scheduleId)
                .and(wrapper -> wrapper.isNull("lock_until").or().lt("lock_until", now));
        KnowledgeDocumentScheduleDO update = new KnowledgeDocumentScheduleDO();
        update.setLockOwner(instanceId);
        update.setLockUntil(lockUntil);
        return scheduleMapper.update(update, updateWrapper) > 0;
    }

    private void releaseLock(Long scheduleId) {
        UpdateWrapper<KnowledgeDocumentScheduleDO> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", scheduleId).eq("lock_owner", instanceId);
        KnowledgeDocumentScheduleDO update = new KnowledgeDocumentScheduleDO();
        update.setLockOwner(null);
        update.setLockUntil(null);
        scheduleMapper.update(update, updateWrapper);
    }

    private void renewLock(Long scheduleId) {
        if (scheduleId == null) {
            return;
        }
        Date lockUntil = new Date(System.currentTimeMillis() + Math.max(lockSeconds, 60) * 1000);
        UpdateWrapper<KnowledgeDocumentScheduleDO> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", scheduleId).eq("lock_owner", instanceId);
        KnowledgeDocumentScheduleDO update = new KnowledgeDocumentScheduleDO();
        update.setLockUntil(lockUntil);
        scheduleMapper.update(update, updateWrapper);
    }

    private String resolveInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return "kb-schedule-" + host + "-" + UUID.randomUUID();
    }

    private String sha256Hex(byte[] content) {
        if (content == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String value = Integer.toHexString(0xff & b);
                if (value.length() == 1) {
                    hex.append('0');
                }
                hex.append(value);
            }
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
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

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record RemoteFetchResult(boolean changed,
                                     byte[] body,
                                     String contentType,
                                     String fileName,
                                     String contentHash,
                                     String etag,
                                     String lastModified,
                                     String message) {
        static RemoteFetchResult skipped(String message, String etag, String lastModified, String contentHash) {
            return new RemoteFetchResult(false, null, null, null, contentHash, etag, lastModified, message);
        }

        static RemoteFetchResult changed(byte[] body,
                                         String contentType,
                                         String fileName,
                                         String contentHash,
                                         String etag,
                                         String lastModified) {
            return new RemoteFetchResult(true, body, contentType, fileName, contentHash, etag, lastModified, null);
        }
    }
}

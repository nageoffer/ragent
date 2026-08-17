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

import cn.hutool.core.thread.ThreadFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleLockManager {

    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    private final KnowledgeScheduleProperties scheduleProperties;

    private final String instancePrefix = resolveInstancePrefix();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
            ThreadFactoryBuilder.create()
                    .setNamePrefix("kb_schedule_lock_heartbeat_")
                    .setDaemon(true)
                    .build()
    );

    public ScheduleLockLease tryAcquire(String scheduleId, Date now) {
        ScheduleLockLease lease = new ScheduleLockLease(scheduleId, nextLockToken());
        int updated = scheduleMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken())
                        .set(KnowledgeDocumentScheduleDO::getLockUntil, computeLockUntil())
                        .eq(KnowledgeDocumentScheduleDO::getId, scheduleId)
                        .and(w -> w.isNull(KnowledgeDocumentScheduleDO::getLockUntil)
                                .or()
                                .lt(KnowledgeDocumentScheduleDO::getLockUntil, now))
        );
        return updated > 0 ? lease : null;
    }

    public boolean renew(ScheduleLockLease lease) {
        if (lease == null) {
            return false;
        }
        return scheduleMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getLockUntil, computeLockUntil())
                        .eq(KnowledgeDocumentScheduleDO::getId, lease.scheduleId())
                        .eq(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken())
        ) > 0;
    }

    public boolean release(ScheduleLockLease lease) {
        if (lease == null) {
            return false;
        }
        return scheduleMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getLockOwner, null)
                        .set(KnowledgeDocumentScheduleDO::getLockUntil, null)
                        .eq(KnowledgeDocumentScheduleDO::getId, lease.scheduleId())
                        .eq(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken())
        ) > 0;
    }

    public ScheduleLockHeartbeat startHeartbeat(ScheduleLockLease lease) {
        long now = System.currentTimeMillis();
        ScheduleLockHeartbeat heartbeat = new ScheduleLockHeartbeat(lease, now, effectiveLockMillis());
        long intervalMillis = computeHeartbeatIntervalMillis();
        ScheduledFuture<?> future = heartbeatExecutor.scheduleWithFixedDelay(
                () -> doHeartbeat(heartbeat),
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
        heartbeat.bind(future);
        return heartbeat;
    }

    public Date computeLockUntil() {
        return new Date(System.currentTimeMillis() + effectiveLockMillis());
    }

    private void doHeartbeat(ScheduleLockHeartbeat heartbeat) {
        if (heartbeat.isClosed() || heartbeat.isLost()) {
            return;
        }
        try {
            if (renew(heartbeat.lease())) {
                heartbeat.markRenewed();
                return;
            }
            heartbeat.markLost();
            log.warn("定时刷新锁已丢失: scheduleId={}, lockToken={}",
                    heartbeat.lease().scheduleId(), heartbeat.lease().lockToken());
        } catch (Exception e) {
            if (heartbeat.isExpiredWithoutConfirmation()) {
                heartbeat.markLost();
                log.warn("定时刷新锁续约失败且已超过安全窗口: scheduleId={}, lockToken={}",
                        heartbeat.lease().scheduleId(), heartbeat.lease().lockToken(), e);
            } else {
                log.warn("定时刷新锁续约失败，将继续重试: scheduleId={}, lockToken={}",
                        heartbeat.lease().scheduleId(), heartbeat.lease().lockToken(), e);
            }
        }
    }

    private long computeHeartbeatIntervalMillis() {
        long effectiveLockSeconds = effectiveLockSeconds();
        long intervalSeconds = Math.max(5, Math.min(effectiveLockSeconds / 3, 60));
        return intervalSeconds * 1000;
    }

    private long effectiveLockMillis() {
        return effectiveLockSeconds() * 1000;
    }

    private long effectiveLockSeconds() {
        return Math.max(scheduleProperties.getLockSeconds(), 60L);
    }

    private String nextLockToken() {
        return instancePrefix + ":" + UUID.randomUUID();
    }

    private static String resolveInstancePrefix() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return "kb-schedule-" + host + "-" + UUID.randomUUID();
    }

    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    public static final class ScheduleLockHeartbeat implements AutoCloseable {

        private final ScheduleLockLease lease;
        private final long lockTtlMillis;
        private final AtomicBoolean lost = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicLong lastConfirmedAt = new AtomicLong();
        private volatile ScheduledFuture<?> future;

        private ScheduleLockHeartbeat(ScheduleLockLease lease, long startAt, long lockTtlMillis) {
            this.lease = lease;
            this.lockTtlMillis = lockTtlMillis;
            this.lastConfirmedAt.set(startAt);
        }

        private void bind(ScheduledFuture<?> future) {
            this.future = future;
        }

        public ScheduleLockLease lease() {
            return lease;
        }

        public boolean isLost() {
            return lost.get();
        }

        private boolean isClosed() {
            return closed.get();
        }

        private void markRenewed() {
            lastConfirmedAt.set(System.currentTimeMillis());
        }

        private boolean isExpiredWithoutConfirmation() {
            return System.currentTimeMillis() - lastConfirmedAt.get() >= lockTtlMillis;
        }

        private void markLost() {
            if (lost.compareAndSet(false, true)) {
                ScheduledFuture<?> scheduledFuture = future;
                if (scheduledFuture != null) {
                    scheduledFuture.cancel(false);
                }
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                ScheduledFuture<?> scheduledFuture = future;
                if (scheduledFuture != null) {
                    scheduledFuture.cancel(false);
                }
            }
        }
    }
}

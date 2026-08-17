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

package com.nageoffer.ai.ragent.core.parser.mineru;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MinerU 共享轮询调度器：把 HTTP 轮询从业务消费者线程剥离到独立调度池
 * <p>
 * 消费者线程仍阻塞在 await 上，只是轮询动作交给 {@link #SCHEDULER_THREADS} 个共享调度线程执行，
 * 数百个 outstanding 任务共用；全局 outstanding 并发由 {@link MinerUDocumentParser} 的分布式信号量控制
 */
@Slf4j
@Component
public class MinerUPollingExecutor {

    private static final int SCHEDULER_THREADS = 4;
    private static final long SHUTDOWN_AWAIT_SECONDS = 10;

    private final MinerUClient client;
    private final MinerUProperties properties;

    private ScheduledExecutorService scheduler;

    public MinerUPollingExecutor(MinerUClient client, MinerUProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        this.scheduler = Executors.newScheduledThreadPool(SCHEDULER_THREADS, namedFactory());
        log.info("MinerUPollingExecutor 启动: schedulerThreads={}", SCHEDULER_THREADS);
    }

    /**
     * 提交任务并阻塞 await 直到完成
     * <p>
     * 调用方业务线程在 {@code future.get()} 上阻塞,但不消耗任何 HTTP / sleep 资源
     *
     * @param batchId MinerU 分配的 batch_id
     * @param timeout 超时时长
     * @return CompletableFuture,完成时携带 DONE 状态的 MinerUStatus(含 zipUrl)
     */
    public CompletableFuture<MinerUStatus> submitAndAwait(String batchId, Duration timeout) {
        if (batchId == null || batchId.isBlank()) {
            CompletableFuture<MinerUStatus> failed = new CompletableFuture<>();
            failed.completeExceptionally(new ServiceException("batchId 不能为空"));
            return failed;
        }

        CompletableFuture<MinerUStatus> future = new CompletableFuture<>();
        Instant deadline = Instant.now().plus(timeout);

        ScheduledFuture<?>[] holder = new ScheduledFuture[1];
        Runnable poll = () -> doPoll(batchId, future, deadline, holder);

        // 最小间隔 100ms(生产配置 5s,这里宽松下限让测试场景能用短间隔)
        long intervalMs = Math.max(100L, properties.getPollIntervalSeconds() * 1000L);
        holder[0] = scheduler.scheduleAtFixedRate(poll, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        // future 完成时(无论成功失败)兜底取消调度任务
        future.whenComplete((status, throwable) -> {
            ScheduledFuture<?> task = holder[0];
            if (task != null) {
                task.cancel(false);
            }
        });

        return future;
    }

    private void doPoll(String batchId,
                        CompletableFuture<MinerUStatus> future,
                        Instant deadline,
                        ScheduledFuture<?>[] holder) {
        if (future.isDone()) {
            return;
        }
        try {
            MinerUStatus status = client.queryResult(batchId);
            if (status.completed()) {
                complete(future, status, holder);
            } else if (status.failed()) {
                completeExceptionally(future, new ServiceException(
                                "MinerU 任务失败 batchId=" + batchId + " err=" + status.errorMessage()), holder);
            } else if (Instant.now().isAfter(deadline)) {
                completeExceptionally(future,
                        new TimeoutException("MinerU 任务超时 batchId=" + batchId), holder);
            }
        } catch (Exception e) {
            // 瞬时网络错误不立即终止,等下一轮重试;超时由 deadline 检查兜底
            log.warn("MinerU 轮询临时异常 batchId={}: {}", batchId, e.getMessage());
            if (Instant.now().isAfter(deadline)) {
                completeExceptionally(future,
                        new ServiceException("MinerU 轮询持续失败到超时 batchId=" + batchId + ": " + e.getMessage()),
                        holder);
            }
        }
    }

    private void complete(CompletableFuture<MinerUStatus> future,
                          MinerUStatus status,
                          ScheduledFuture<?>[] holder) {
        if (future.complete(status)) {
            cancelPolling(holder);
        }
    }

    private void completeExceptionally(CompletableFuture<MinerUStatus> future,
                                       Throwable error,
                                       ScheduledFuture<?>[] holder) {
        if (future.completeExceptionally(error)) {
            cancelPolling(holder);
        }
    }

    private void cancelPolling(ScheduledFuture<?>[] holder) {
        ScheduledFuture<?> task = holder[0];
        if (task != null) {
            task.cancel(false);
        }
    }

    @PreDestroy
    void shutdown() {
        if (scheduler == null) {
            return;
        }
        log.info("MinerUPollingExecutor 优雅停机中，等待 active 任务最多 {}s", SHUTDOWN_AWAIT_SECONDS);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("MinerUPollingExecutor 强制停机");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    private static ThreadFactory namedFactory() {
        AtomicInteger seq = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, "minerU-poll-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}

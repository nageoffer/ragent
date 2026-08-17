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

package com.nageoffer.ai.ragent.rag.service.ratelimit;

import cn.hutool.core.util.IdUtil;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * 分布式公平限流器
 */
@Slf4j
public final class FairDistributedRateLimiter {

    private static final String LUA_PATH = "lua/queue_claim_atomic.lua";
    /**
     * entry TTL 在 maxWaitMillis 之上的额外缓冲，避免毫秒级时钟漂移导致存活条目被误判为僵尸
     */
    private static final long ENTRY_TTL_BUFFER_MILLIS = 5_000L;

    private final String name;
    private final RedissonClient redissonClient;
    private final IntSupplier maxPermitsSupplier;
    private final IntSupplier leaseSecondsSupplier;
    private final IntSupplier pollIntervalMsSupplier;

    private final String semaphoreKey;
    private final String queueKey;
    private final String queueSeqKey;
    private final String notifyTopicKey;
    private final String entryKeyPrefix;
    private final String claimLua;

    private final ScheduledExecutorService scheduler;
    private final PollNotifier pollNotifier;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile int notifyListenerId = -1;

    public FairDistributedRateLimiter(String name,
                                      RedissonClient redissonClient,
                                      IntSupplier maxPermitsSupplier,
                                      IntSupplier leaseSecondsSupplier,
                                      IntSupplier pollIntervalMsSupplier) {
        this.name = Objects.requireNonNull(name);
        this.redissonClient = Objects.requireNonNull(redissonClient);
        this.maxPermitsSupplier = Objects.requireNonNull(maxPermitsSupplier);
        this.leaseSecondsSupplier = Objects.requireNonNull(leaseSecondsSupplier);
        this.pollIntervalMsSupplier = Objects.requireNonNull(pollIntervalMsSupplier);

        this.semaphoreKey = name + ":semaphore";
        this.queueKey = name + ":queue";
        this.queueSeqKey = name + ":queue:seq";
        this.notifyTopicKey = name + ":queue:notify";
        this.entryKeyPrefix = name + ":entry:";
        this.claimLua = loadLuaScript();

        String threadPrefix = name.replace(':', '_');
        int schedulerSize = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        AtomicInteger threadCounter = new AtomicInteger();
        this.scheduler = new ScheduledThreadPoolExecutor(schedulerSize, r -> {
            Thread t = new Thread(r);
            t.setName(threadPrefix + "_scheduler_" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.pollNotifier = new PollNotifier(this::availablePermits, scheduler);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        // 一次性初始化 semaphore permits 数；trySetPermits 自身幂等，仅首次生效
        // 后续 acquire/availablePermits 不再重复调用，避免每个 poller 多一次 Redis 往返
        redissonClient.getPermitExpirableSemaphore(semaphoreKey).trySetPermits(maxPermitsSupplier.getAsInt());
        RTopic topic = redissonClient.getTopic(notifyTopicKey);
        notifyListenerId = topic.addListener(String.class, (channel, msg) -> pollNotifier.fire());
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        if (notifyListenerId != -1) {
            redissonClient.getTopic(notifyTopicKey).removeListener(notifyListenerId);
            notifyListenerId = -1;
        }
        scheduler.shutdown();
        awaitShutdown(scheduler);
        pollNotifier.clear();
    }

    /**
     * 非阻塞地排队抢占一个 permit
     */
    public void acquire(AcquireRequest req) {
        Ticket ticket = new Ticket(req);
        if (req.cancelBinder() != null) {
            req.cancelBinder().accept(ticket::cancel);
        }
        // entry 存活标记必须先于入队写入，否则 race 窗口内的并发 claim 会把刚入队的条目当僵尸 ZREM
        setEntryMarker(ticket.requestId, req.maxWaitMillis());
        RScoredSortedSet<String> queue = redissonClient.getScoredSortedSet(queueKey, StringCodec.INSTANCE);
        queue.add(nextQueueSeq(), ticket.requestId);
        if (tryAcquireIfReady(ticket)) {
            return;
        }
        scheduleQueuePoll(ticket);
    }

    // ==================== Ticket 状态机 ====================

    /**
     * 单 CAS 协调点。终态互斥：状态一旦从 PENDING 转走就不再变更，业务回调最多触发一次
     * 资源清理 ({@link Ticket#cleanup()}) 与状态机解耦，幂等执行
     */
    private enum State {PENDING, GRANTED, TIMED_OUT, CANCELLED}

    private final class Ticket {
        final String requestId = IdUtil.getSnowflakeNextIdStr();
        final long deadline;
        final AcquireRequest req;
        final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
        final AtomicReference<String> permitRef = new AtomicReference<>();
        volatile ScheduledFuture<?> future;

        Ticket(AcquireRequest req) {
            this.req = req;
            this.deadline = System.currentTimeMillis() + req.maxWaitMillis();
        }

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        boolean isPending() {
            return state.get() == State.PENDING;
        }

        /**
         * 外部取消（emitter 完结/超时/出错）：幂等释放队列资源 + 注销，best-effort 抑制业务回调
         *
         * <p>GRANTED 状态下不释放 permit —— permit 已经被 {@link #grant} 包装的 try/finally 接管，
         * 业务执行完毕（含异常）会在 finally 释放。这里跨界释放会导致并发请求拿到尚在使用的 permit
         */
        void cancel() {
            state.compareAndSet(State.PENDING, State.CANCELLED);
            cleanup();
        }

        /**
         * 排队超时：CAS 抢占终态后 cleanup + 在 caller executor 上跑 onTimeout
         */
        void timeout() {
            if (!state.compareAndSet(State.PENDING, State.TIMED_OUT)) {
                return;
            }
            cleanup();
            submitSafely(req.onTimeout(), "onTimeout");
        }

        /**
         * 拿到 permit。CAS 抢占 GRANTED 终态后将 permit 生命周期交给业务（try/finally 释放）
         *
         * <p>permitRef 设值与 CAS 顺序：先 set，再 CAS。这样并发 cancel/timeout 路径在 CAS 失败
         * 时能看到 permit 并正确释放，避免 grant 与 cancel 时序竞争导致 permit 泄漏
         */
        boolean grant(String permitId) {
            permitRef.set(permitId);
            if (!state.compareAndSet(State.PENDING, State.GRANTED)) {
                // 已被 cancel/timeout 抢占。permitRef 可能已被对方 cleanup 清空，CAS 防双重释放
                if (permitRef.compareAndSet(permitId, null)) {
                    releasePermitQuietly(permitId);
                    publishQueueNotify();
                }
                return false;
            }
            unregisterFromNotifier();
            cancelFutureQuietly();
            Runnable wrapped = () -> {
                try {
                    req.onAcquired().run();
                } finally {
                    releaseHeldPermit();
                }
            };
            try {
                req.onAcquiredExecutor().execute(wrapped);
                return true;
            } catch (RejectedExecutionException ex) {
                log.warn("[{}] onAcquired 提交失败，降级为 timeout 拒绝路径", name, ex);
                releaseHeldPermit();   // 业务未运行，必须显式释放（cleanup 在 GRANTED 状态不会释放 permit）
                cleanup();
                submitSafely(req.onTimeout(), "onTimeout(fallback)");
                return false;
            }
        }

        /**
         * 释放当前 Ticket 持有的 permit（若有）。线程安全 + 幂等
         */
        void releaseHeldPermit() {
            String pid = permitRef.getAndSet(null);
            if (pid != null) {
                releasePermitQuietly(pid);
                publishQueueNotify();
            }
        }

        /**
         * 幂等清理：移队、删除 entry 标记、释放 permit（仅在非 GRANTED 状态下）、注销 poller、取消 future
         *
         * <p>GRANTED 状态下 permit 已由 grant 的包装 Runnable 接管，cleanup 不再释放，
         * 否则会在业务运行期间把 permit 还给 semaphore，等价于把同一 slot 让给另一个请求
         */
        void cleanup() {
            boolean removed = false;
            try {
                removed = redissonClient.getScoredSortedSet(queueKey, StringCodec.INSTANCE).remove(requestId);
            } catch (Exception ex) {
                log.debug("[{}] 移除队列失败 (requestId={})", name, requestId, ex);
            }
            deleteEntryMarker(requestId);

            boolean releasedPermit = false;
            if (state.get() != State.GRANTED) {
                String permitId = permitRef.getAndSet(null);
                if (permitId != null) {
                    releasePermitQuietly(permitId);
                    releasedPermit = true;
                }
            }
            if (removed || releasedPermit) {
                publishQueueNotify();
            }
            unregisterFromNotifier();
            cancelFutureQuietly();
        }

        void unregisterFromNotifier() {
            pollNotifier.unregister(requestId);
        }

        void cancelFutureQuietly() {
            ScheduledFuture<?> f = future;
            if (f != null && !f.isCancelled()) {
                f.cancel(false);
            }
        }

        private void submitSafely(Runnable r, String label) {
            try {
                req.onAcquiredExecutor().execute(r);
            } catch (Exception ex) {
                log.warn("[{}] {} 提交失败，回调被丢弃", name, label, ex);
            }
        }
    }

    // ==================== 抢占核心 ====================

    private boolean tryAcquireIfReady(Ticket ticket) {
        if (!ticket.isPending()) {
            return false;
        }
        int avail = availablePermits();
        if (avail <= 0) {
            return false;
        }
        long claimedScore = claimIfReady(ticket.requestId, avail);
        if (claimedScore < 0L) {
            return false;
        }
        String permitId = tryAcquirePermit();
        if (permitId == null) {
            // 队头但无 permit：按原 score 重入队，保留排队位次（公平性）
            // 与 cancel/timeout 的 race：claimIfReady 已 ZREM，cleanup 的 remove 在此刻是 no-op；
            // 必须 add 后回查 state，若已终态则自行回滚，避免僵尸条目永久占据队头窗口
            setEntryMarker(ticket.requestId, Math.max(1, ticket.deadline - System.currentTimeMillis()));
            RScoredSortedSet<String> queue = redissonClient.getScoredSortedSet(queueKey, StringCodec.INSTANCE);
            queue.add(claimedScore, ticket.requestId);
            publishQueueNotify();
            if (!ticket.isPending()) {
                queue.remove(ticket.requestId);
                deleteEntryMarker(ticket.requestId);
            }
            return false;
        }
        if (!ticket.isPending()) {
            // claim 与 acquire 之间被取消/超时：必须释放 permit 并通知，否则其他等待者要等下一次 poll
            releasePermitQuietly(permitId);
            publishQueueNotify();
            return false;
        }
        publishQueueNotify();
        return ticket.grant(permitId);
    }

    private void scheduleQueuePoll(Ticket ticket) {
        int interval = Math.max(50, pollIntervalMsSupplier.getAsInt());
        Runnable poller = () -> {
            if (!ticket.isPending()) {
                ticket.unregisterFromNotifier();
                ticket.cancelFutureQuietly();
                return;
            }
            if (System.currentTimeMillis() > ticket.deadline) {
                ticket.timeout();
                return;
            }
            tryAcquireIfReady(ticket);
        };
        ticket.future = scheduler.scheduleAtFixedRate(poller, interval, interval, TimeUnit.MILLISECONDS);
        pollNotifier.register(ticket.requestId, poller);
    }

    // ==================== Redis 操作 ====================

    private String tryAcquirePermit() {
        RPermitExpirableSemaphore sem = redissonClient.getPermitExpirableSemaphore(semaphoreKey);
        try {
            return sem.tryAcquire(0, leaseSecondsSupplier.getAsInt(), TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private int availablePermits() {
        return redissonClient.getPermitExpirableSemaphore(semaphoreKey).availablePermits();
    }

    private void releasePermitQuietly(String permitId) {
        try {
            redissonClient.getPermitExpirableSemaphore(semaphoreKey).release(permitId);
        } catch (Exception ex) {
            log.debug("[{}] 释放 permit 失败（可能已过期）：{}", name, ex.getMessage());
        }
    }

    /**
     * 写入 entry 存活标记，TTL = 等待预算 + 缓冲。JVM 崩溃后 Key 自然过期，
     * 后续 {@link #claimIfReady} 在 Lua 内会把对应 ZSet 条目当僵尸清理掉，避免永久占据队头窗口
     */
    private void setEntryMarker(String requestId, long remainingMillis) {
        long ttlMillis = Math.max(remainingMillis, 1L) + ENTRY_TTL_BUFFER_MILLIS;
        try {
            RBucket<String> bucket = redissonClient.getBucket(entryKeyPrefix + requestId, StringCodec.INSTANCE);
            bucket.set("1", Duration.ofMillis(ttlMillis));
        } catch (Exception ex) {
            log.debug("[{}] 写入 entry 标记失败 (requestId={})", name, requestId, ex);
        }
    }

    private void deleteEntryMarker(String requestId) {
        try {
            redissonClient.getBucket(entryKeyPrefix + requestId, StringCodec.INSTANCE).delete();
        } catch (Exception ex) {
            log.debug("[{}] 删除 entry 标记失败 (requestId={})", name, requestId, ex);
        }
    }

    /**
     * @return 成功返回 ticket 的原始 score（用于失败时按原位次重入队），未 claim 返回 -1
     */
    private long claimIfReady(String requestId, int availablePermits) {
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        List<Object> result = script.eval(
                RScript.Mode.READ_WRITE,
                claimLua,
                RScript.ReturnType.LIST,
                List.of(queueKey),
                requestId,
                String.valueOf(availablePermits),
                entryKeyPrefix
        );
        if (result == null || result.isEmpty() || parseLong(result.get(0)) != 1L) {
            return -1L;
        }
        return result.size() >= 2 ? parseLong(result.get(1)) : nextQueueSeq();
    }

    private long nextQueueSeq() {
        RAtomicLong seq = redissonClient.getAtomicLong(queueSeqKey);
        return seq.incrementAndGet();
    }

    private void publishQueueNotify() {
        redissonClient.getTopic(notifyTopicKey).publish("permit_changed");
    }

    // ==================== 辅助 ====================

    private static long parseLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static String loadLuaScript() {
        try {
            ClassPathResource resource = new ClassPathResource(LUA_PATH);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("加载 Lua 脚本失败：" + LUA_PATH, ex);
        }
    }

    private static void awaitShutdown(ScheduledExecutorService exec) {
        try {
            if (!exec.awaitTermination(3, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException ex) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 公开类型 ====================

    /**
     * 抢占请求参数
     */
    @Builder
    public record AcquireRequest(long maxWaitMillis,
                                 Runnable onAcquired,
                                 Runnable onTimeout,
                                 Executor onAcquiredExecutor,
                                 Consumer<Runnable> cancelBinder) {
        public AcquireRequest {
            Objects.requireNonNull(onAcquired);
            Objects.requireNonNull(onTimeout);
            Objects.requireNonNull(onAcquiredExecutor);
            if (maxWaitMillis <= 0) {
                throw new IllegalArgumentException("maxWaitMillis must be > 0");
            }
        }
    }

    // ==================== PollNotifier ====================

    /**
     * 跨实例 RTopic 通知到达后，批量唤醒本进程所有 poller
     *
     * <p>通过 {@code firing} CAS + {@code pendingNotifications} 计数做合并：连续到达的多次通知
     * 只触发一次扫描，避免风暴。复用外部 scheduler 执行扫描，无需独立线程
     */
    private static final class PollNotifier {

        private final IntSupplier permitSupplier;
        private final Executor executor;
        private final ConcurrentHashMap<String, Runnable> pollers = new ConcurrentHashMap<>();
        private final AtomicBoolean firing = new AtomicBoolean(false);
        private final AtomicInteger pendingNotifications = new AtomicInteger(0);

        PollNotifier(IntSupplier permitSupplier, Executor executor) {
            this.permitSupplier = permitSupplier;
            this.executor = executor;
        }

        void register(String requestId, Runnable poller) {
            pollers.put(requestId, poller);
        }

        void unregister(String requestId) {
            pollers.remove(requestId);
        }

        void fire() {
            pendingNotifications.incrementAndGet();
            if (!firing.compareAndSet(false, true)) {
                return;
            }
            executor.execute(() -> {
                do {
                    pendingNotifications.set(0);
                    try {
                        if (permitSupplier.getAsInt() <= 0) {
                            // permit 已耗尽，本轮不必扫描所有 poller。下一次真正的 release 会发新通知重新驱动
                            break;
                        }
                        for (Runnable poller : pollers.values()) {
                            try {
                                poller.run();
                            } catch (Exception ex) {
                                log.debug("poller 执行异常", ex);
                            }
                        }
                    } finally {
                        firing.set(false);
                    }
                } while (pendingNotifications.get() > 0 && firing.compareAndSet(false, true));
            });
        }

        void clear() {
            pollers.clear();
        }
    }
}

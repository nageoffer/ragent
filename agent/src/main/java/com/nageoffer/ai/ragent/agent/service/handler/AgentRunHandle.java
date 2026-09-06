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

package com.nageoffer.ai.ragent.agent.service.handler;

import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次 Agent 运行的生命周期句柄，complete/cancel/fail 三条出口 CAS 互斥
 */
@Slf4j
public class AgentRunHandle {

    /**
     * 打断后等待框架存盘的超时时间
     */
    private static final long GRACEFUL_INTERRUPT_WAIT_MS = 2000L;

    @Getter
    private final String taskId;

    /**
     * SSE 发送器，增量事件由调用方直接写
     */
    @Getter
    private final SseEmitterSender sender;

    private final StreamTaskManager taskManager;
    private final AtomicBoolean settled = new AtomicBoolean(false);

    /**
     * 断流只执行一次，取消广播线程与补掐路径可能各调一次
     */
    private final AtomicBoolean interruptTriggered = new AtomicBoolean(false);

    /**
     * 释放钩子队列，保证每个钩子恰好执行一次
     */
    private final Object releaseLock = new Object();
    private final List<Runnable> releaseHooks = new ArrayList<>();
    private boolean released;

    /**
     * 上游流终止信号，打断时等待框架完成存盘
     */
    private final CountDownLatch upstreamTerminated = new CountDownLatch(1);

    private volatile Disposable disposable;
    private volatile Runnable interruptAction;

    /**
     * -- GETTER --
     * 是否走的失败出口，失败时释放钩子需要补一次存盘
     */
    @Getter
    private volatile boolean failed;

    /**
     * -- GETTER --
     * 是否走了强制断流，dispose 掐链后框架的中断存盘跑不到，释放钩子需要补一次
     */
    @Getter
    private volatile boolean forcedDisposal;

    /**
     * -- GETTER --
     * 是否走的取消出口，取消抢在句柄绑定前结算时调用方靠它补掐上游
     */
    @Getter
    private volatile boolean cancelledExit;

    public AgentRunHandle(String taskId, SseEmitterSender sender, StreamTaskManager taskManager) {
        this.taskId = taskId;
        this.sender = sender;
        this.taskManager = taskManager;
    }

    /**
     * 绑定上游订阅与打断动作，取消时先打断等存盘，超时再断流
     */
    public void bindStream(Disposable disposable, Runnable interruptAction) {
        this.disposable = disposable;
        this.interruptAction = interruptAction;
    }

    /**
     * 登记释放钩子，三条出口都会执行；结算后登记的钩子当场补跑
     */
    public void onRelease(Runnable hook) {
        if (hook == null) {
            return;
        }
        synchronized (releaseLock) {
            if (!released) {
                releaseHooks.add(hook);
                return;
            }
        }
        runReleaseHook(hook);
    }

    /**
     * 标记上游流已终止（完成、异常或被断流）
     */
    public void markUpstreamTerminated() {
        upstreamTerminated.countDown();
    }

    /**
     * 先中断框架等其存盘，超时再 dispose 断流；顺序反了会丢失本轮 Agent 状态
     */
    public void interruptUpstream() {
        if (!interruptTriggered.compareAndSet(false, true)) {
            return;
        }
        Runnable interrupt = interruptAction;
        if (interrupt != null) {
            boolean graceful = false;
            try {
                interrupt.run();
                graceful = awaitUpstreamTermination();
            } catch (Exception e) {
                // 打断动作异常不能挡住断流，否则 ReAct 循环会空跑到迭代上限
                log.error("打断动作执行异常，转为强制断流, taskId: {}", taskId, e);
            }
            forcedDisposal = !graceful;
        }
        // 框架已自行收尾时这里是空操作，强制断流才真正掐链
        Disposable current = disposable;
        if (current != null) {
            current.dispose();
        }
    }

    /**
     * 等待框架完成中断分支并存盘，返回上游是否在期限内正常结束
     */
    private boolean awaitUpstreamTermination() {
        try {
            boolean terminated = upstreamTerminated.await(GRACEFUL_INTERRUPT_WAIT_MS, TimeUnit.MILLISECONDS);
            if (!terminated) {
                log.warn("等待上游流响应打断超时，转为强制断流, taskId: {}", taskId);
            }
            return terminated;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean isCancelled() {
        return taskManager.isCancelled(taskId);
    }

    /**
     * 是否已完成收尾
     */
    public boolean isSettled() {
        return settled.get();
    }

    public void complete(Runnable body) {
        if (settle(body)) {
            sender.complete();
        }
    }

    public void cancel(Runnable body) {
        if (settle(() -> {
            cancelledExit = true;
            body.run();
        })) {
            sender.complete();
        }
    }

    public void fail(Runnable body) {
        // failed 置在收尾体里，释放钩子执行时已可读
        if (settle(() -> {
            failed = true;
            body.run();
        })) {
            sender.complete();
        }
    }

    /**
     * CAS 保证收尾体只跑一次，无论成败都注销任务并释放资源
     */
    private boolean settle(Runnable body) {
        if (!settled.compareAndSet(false, true)) {
            return false;
        }
        try {
            body.run();
        } catch (Exception e) {
            log.error("Agent 运行收尾处理失败, taskId: {}", taskId, e);
        } finally {
            taskManager.unregister(taskId);
            runReleaseHooks();
        }
        return true;
    }

    private void runReleaseHooks() {
        List<Runnable> pending;
        synchronized (releaseLock) {
            released = true;
            pending = new ArrayList<>(releaseHooks);
            releaseHooks.clear();
        }
        // 锁外执行，避免钩子内再登记钩子时死锁
        pending.forEach(this::runReleaseHook);
    }

    private void runReleaseHook(Runnable hook) {
        try {
            hook.run();
        } catch (Exception e) {
            // 单个钩子失败不能拖累其余释放动作
            log.error("Agent 运行释放钩子执行失败, taskId: {}", taskId, e);
        }
    }
}

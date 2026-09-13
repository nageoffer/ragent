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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次 Agent 运行的生命周期句柄：持 taskId、SSE 通道、上游订阅与释放钩子
 * complete / cancel / fail 三条出口经同一个 CAS 互斥，胜者跑收尾体，随后统一注销任务并跑释放钩子
 */
@Slf4j
public class AgentRunHandle {

    @Getter
    private final String taskId;    // 任务 ID，唯一标识一次 Agent 运行

    /**
     * 流中途的增量事件仍由使用方直接写，句柄只管三条出口的互斥与收尾
     */
    @Getter
    private final SseEmitterSender sender;

    private final StreamTaskManager taskManager;
    private final AtomicBoolean settled = new AtomicBoolean(false); //原子布尔标记，代表「是否已经执行过收尾」

    /**
     * 钩子队列与「已释放」旗标同锁：登记与释放交叠时，钩子要么进队列被排干，要么就地补跑，恰好一次
     */
    private final Object releaseLock = new Object();    
    private final List<Runnable> releaseHooks = new ArrayList<>();
    private boolean released;

    private volatile Disposable disposable;
    private volatile Runnable interruptAction;

    /**
     * 本轮 Agent 运行的生命周期句柄，管理释放、中断、回调钩子。
     */
    public AgentRunHandle(String taskId, SseEmitterSender sender, StreamTaskManager taskManager) {
        this.taskId = taskId;
        this.sender = sender;
        this.taskManager = taskManager;
    }

    /**
     * 绑定上游订阅与打断动作，取消时先断流再打断，二者缺一都会让 ReAct 循环空跑
     */
    public void bindStream(Disposable disposable, Runnable interruptAction) {
        this.disposable = disposable;
        this.interruptAction = interruptAction;
    }

    /**
     * 登记释放钩子，三条出口都会执行：并发闸门、内存态驱逐等一次性资源挂这里
     * 结算之后才登记的钩子当场补跑，否则后人往 subscribe 之后加资源会被静默泄漏
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
     * 断开上游：取消协议里先于收尾体执行
     */
    public void interruptUpstream() {
        Disposable current = disposable;
        if (current != null) {
            current.dispose();
        }
        Runnable interrupt = interruptAction;
        if (interrupt != null) {
            interrupt.run();
        }
    }

    public boolean isCancelled() {
        return taskManager.isCancelled(taskId);
    }

    /**
     * 是否已走过收尾路，供 SSE 生命周期回调判断上游还要不要回收
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
        if (settle(body)) {
            sender.complete();
        }
    }

    public void fail(Throwable error, Runnable body) {
        // 流已以 text/event-stream 提交后，错误不能经 sender.fail(completeWithError) 硬断：
        // 容器会把异常再派发给全局异常处理器，而响应 Content-Type 已无可序列化对象，
        // 只会刷出 No converter 的三层噪音。失败态与 complete/cancel 一致，走 settle 收尾
        // （注销任务 + 闸门/缓存/记忆释放钩子）后正常关闭连接；失败告知由调用方在 body 里
        // 以 SSE error 事件发给前端。
        if (settle(body)) {
            sender.complete();
        }
    }

    /**
     * 收尾体只跑一次；无论其成败都要注销任务并释放资源，否则异常路径会漏掉闸门
     * 收尾体的异常就地咽下：一旦进入settle方法，settled=true ,已让超时守卫失效，异常再往上抛就没人关通道，SSE 会一直挂到超时
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
            taskManager.unregister(taskId); //注销任务
            runReleaseHooks();              //运行释放钩子
        }
        return true;
    }

    //
    private void runReleaseHooks() {
        List<Runnable> pending; //待执行的，挂起的
        synchronized (releaseLock) {
            released = true;
            pending = new ArrayList<>(releaseHooks);
            releaseHooks.clear();
        }
        // 钩子在锁外执行，免得它反过来登记新钩子时自锁
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

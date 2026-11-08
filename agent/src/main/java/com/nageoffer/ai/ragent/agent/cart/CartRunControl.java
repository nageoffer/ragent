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

package com.nageoffer.ai.ragent.agent.cart;

import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;

/** 服务端本轮授权快照和取消标记，不交给模型填写。 */
public final class CartRunControl {
    private final CartOperationStore store;
    private final String taskId;
    private final List<ToolUseBlock> approvals;
    private boolean cancelled;
    private boolean disconnected;

    public CartRunControl(CartOperationStore store, String taskId, List<ConfirmResult> results) {
        this.store = store;
        this.taskId = taskId;
        this.approvals = results == null ? List.of() : results.stream().filter(ConfirmResult::isConfirmed)
                .map(ConfirmResult::getToolCall).toList();
    }
    public boolean approved(ToolUseBlock call) {
        return call != null && approvals.stream().anyMatch(a -> a.getId().equals(call.getId())
                && a.getName().equals(call.getName()) && a.getInput().equals(call.getInput()));
    }
    public synchronized boolean cancelled() { return cancelled; }
    /** 先同步关闭本地写入口，不等待异步取消任务完成持久化。 */
    public synchronized void markDisconnected() {
        disconnected = true;
        cancelled = true;
    }
    public synchronized void markCancelled() {
        cancelled = true;
    }
    public synchronized void cancel() {
        cancelled = true;
        store.stopTask(taskId, disconnected);
    }
}

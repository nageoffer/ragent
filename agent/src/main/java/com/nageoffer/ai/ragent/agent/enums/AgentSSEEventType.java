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

package com.nageoffer.ai.ragent.agent.enums;

import lombok.AllArgsConstructor;

/**
 * Agent 模式 SSE 事件协议，与 workflow 协议两套分立
 */
@AllArgsConstructor
public enum AgentSSEEventType {

    /**
     * 会话与任务元信息
     */
    META("meta"),

    /**
     * 增量消息（response / think）
     */
    MESSAGE("message"),

    /**
     * 工具进度 {name, displayName, status: start|end, result, ok}
     */
    TOOL("tool"),

    /**
     * 运行提示（如达到迭代上限的熔断预告），不落库
     */
    HINT("hint"),

    /**
     * 回复完成
     */
    FINISH("finish"),

    /**
     * 流结束
     */
    DONE("done"),

    /**
     * 流式会话失败：错误经 SSE 事件通道告知前端（流已以 text/event-stream 提交，无法回写 HTTP 错误体）
     */
    ERROR("error"),

    /**
     * 用户取消
     */
    CANCEL("cancel");

    private final String value;

    public String value() {
        return value;
    }
}

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

package com.nageoffer.ai.ragent.rag.enums;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum SSEEventType {

    /**
     * 会话与任务的元信息事件
     */
    META("meta"),

    /**
     * 增量消息事件
     */
    MESSAGE("message"),

    /**
     * 模型回复完成事件
     */
    FINISH("finish"),

    /**
     * 完成事件
     */
    DONE("done"),

    /**
     * 取消事件
     */
    CANCEL("cancel"),

    /**
     * 拒绝事件
     */
    REJECT("reject");

    private final String value;

    /**
     * SSE 事件名称（与前端约定一致）
     */
    public String value() {
        return value;
    }
}

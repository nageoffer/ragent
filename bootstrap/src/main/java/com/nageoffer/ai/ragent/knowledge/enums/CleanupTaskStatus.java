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

package com.nageoffer.ai.ragent.knowledge.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * outbox 清理任务状态
 */
@Getter
@RequiredArgsConstructor
public enum CleanupTaskStatus {

    /**
     * 待执行
     */
    PENDING("pending"),

    /**
     * 已被 worker 领取，正在执行
     */
    RUNNING("running"),

    /**
     * 执行成功
     */
    SUCCESS("success"),

    /**
     * 重试耗尽，进入死信，需人工介入
     */
    FAILED("failed");

    public static final String PENDING_CODE = "pending";
    public static final String RUNNING_CODE = "running";

    private final String code;
}

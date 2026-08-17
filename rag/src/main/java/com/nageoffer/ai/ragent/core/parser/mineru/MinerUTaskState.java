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

/**
 * MinerU 任务状态
 * <p>
 * 与 MinerU SaaS 返回的 {@code state} 字段映射:
 * <ul>
 *   <li>{@code waiting-file / pending / running / converting} → {@link #RUNNING}</li>
 *   <li>{@code done} → {@link #DONE}</li>
 *   <li>{@code failed} → {@link #FAILED}</li>
 *   <li>无法识别的状态 → {@link #UNKNOWN}(视为 running 继续轮询)</li>
 * </ul>
 */
public enum MinerUTaskState {
    RUNNING,
    DONE,
    FAILED,
    UNKNOWN;

    /**
     * 从 MinerU 字段值映射到枚举
     */
    public static MinerUTaskState parse(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        return switch (raw.toLowerCase()) {
            case "done", "success", "succeeded", "completed" -> DONE;
            case "failed", "fail", "error" -> FAILED;
            case "waiting-file", "pending", "running", "converting", "queueing", "queue" -> RUNNING;
            default -> UNKNOWN;
        };
    }
}

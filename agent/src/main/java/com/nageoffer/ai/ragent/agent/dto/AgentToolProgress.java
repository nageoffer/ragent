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

package com.nageoffer.ai.ragent.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE block 事件的工具载荷，kind 为 tool；状态与落库块同名同值，result 和 ok 仅终态时携带
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentToolProgress(String kind, String toolCallId, String name, String displayName, String status,
                                String result, Boolean ok, String at, String batchId, Integer callIndex,
                                Long startedAt, Long endedAt, Long durationMs, String durationSource) {

    /**
     * 从块投影，SSE 与落库同源，避免刷新前后出现两个不同的数
     */
    public static AgentToolProgress of(AgentBlock block) {
        return new AgentToolProgress(AgentBlock.KIND_TOOL, block.getToolCallId(), block.getName(), block.getDisplayName(),
                block.getStatus(), block.getResult(), ok(block.getStatus()), block.getAt(), block.getBatchId(),
                block.getCallIndex(), block.getStartedAt(), block.getEndedAt(), block.getDurationMs(),
                block.getDurationSource());
    }

    /**
     * 只在终态表态，非终态返回 null
     */
    private static Boolean ok(String status) {
        return switch (status) {
            case "done" -> Boolean.TRUE;
            case "failed", "denied", "interrupted" -> Boolean.FALSE;
            default -> null;
        };
    }
}

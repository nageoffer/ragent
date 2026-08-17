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

package com.nageoffer.ai.ragent.rag.core.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 参数提取结局
 * <p>
 * 区分三态，供消费端决定是否调用工具：
 * - {@link Status#SUCCESS}：参数已就绪，可调用工具
 * - {@link Status#NEED_CLARIFICATION}：缺少必填参数（用户未提供），不调用工具、需向用户追问，missingRequired 列出缺失项
 * - {@link Status#FAILED}：无法提取到有效参数（协议畸形 / 值非法），不调用工具
 *
 * @param status          提取结局
 * @param params          已提取的有效参数（SUCCESS 用于调用；其余态仅作记录）
 * @param missingRequired 用户未提供的必填参数名（仅 NEED_CLARIFICATION 非空）
 */
public record McpExtractionResult(Status status, Map<String, Object> params, List<String> missingRequired) {

    public enum Status {
        SUCCESS,
        NEED_CLARIFICATION,
        FAILED
    }

    public static McpExtractionResult success(Map<String, Object> params) {
        return new McpExtractionResult(Status.SUCCESS, params, List.of());
    }

    public static McpExtractionResult needClarification(Map<String, Object> params, List<String> missingRequired) {
        return new McpExtractionResult(Status.NEED_CLARIFICATION, params, List.copyOf(missingRequired));
    }

    public static McpExtractionResult failed() {
        return new McpExtractionResult(Status.FAILED, Map.of(), List.of());
    }
}

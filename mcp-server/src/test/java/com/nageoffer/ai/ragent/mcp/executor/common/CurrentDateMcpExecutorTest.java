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

package com.nageoffer.ai.ragent.mcp.executor.common;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentDateMcpExecutorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-31T16:30:00Z"), ZoneOffset.UTC);

    private final CurrentDateMcpExecutor executor = new CurrentDateMcpExecutor(FIXED_CLOCK);

    @Test
    void shouldExposeReadOnlyCurrentDateTool() {
        Tool tool = executor.currentDateToolSpecification().tool();

        assertEquals("current_date", tool.name());
        assertEquals(List.of(), tool.inputSchema().required());
        assertTrue(tool.inputSchema().properties().containsKey("timezone"));
        assertTrue(tool.annotations().readOnlyHint());
    }

    @Test
    void shouldUseShanghaiTimezoneByDefault() {
        CallToolResult result = executor.handleCall(request(Map.of()));

        assertFalse(result.isError());
        assertTrue(text(result).contains("当前日期: 2026-09-01"));
        assertTrue(text(result).contains("星期: 星期二"));
        assertTrue(text(result).contains("时区: Asia/Shanghai"));
    }

    @Test
    void shouldResolveDateInRequestedTimezone() {
        CallToolResult result = executor.handleCall(request(Map.of("timezone", "UTC")));

        assertFalse(result.isError());
        assertTrue(text(result).contains("当前日期: 2026-08-31"));
        assertTrue(text(result).contains("星期: 星期一"));
        assertTrue(text(result).contains("时区: UTC"));
    }

    @Test
    void shouldRejectInvalidTimezone() {
        CallToolResult result = executor.handleCall(request(Map.of("timezone", "Mars/Olympus")));

        assertTrue(result.isError());
        assertTrue(text(result).contains("IANA 时区"));
    }

    private static CallToolRequest request(Map<String, Object> arguments) {
        return new CallToolRequest("current_date", arguments);
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }
}

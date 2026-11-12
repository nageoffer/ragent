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

package com.nageoffer.ai.ragent.mcp.executor.enterprise;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeetingRoomMcpExecutorTest {

    private final MeetingRoomMcpExecutor executor = new MeetingRoomMcpExecutor();

    @Test
    void shouldExposeRoomIdAsIdInsteadOfRoomName() {
        Tool tool = executor.meetingRoomBookToolSpecification().tool();
        Map<?, ?> roomIdSchema = (Map<?, ?>) tool.inputSchema().properties().get("roomId");

        assertEquals("会议室ID", roomIdSchema.get("title"));
        assertTrue(roomIdSchema.get("description").toString().contains("不是会议室名称"));
        assertEquals(List.of("A701", "A703", "B305", "B308", "C502", "C509"), roomIdSchema.get("enum"));
    }

    @Test
    void shouldReturnAliStyleRoomNamesAndCompactLocations() {
        CallToolResult result = executor.meetingRoomQueryToolSpecification().callHandler()
                .apply(null, request("meeting_room_query", Map.of("date", "2026-09-07")));
        String text = text(result);

        assertFalse(result.isError());
        assertTrue(text.contains("光明顶 | ID: A701 | 西溪园区A楼7F"));
        assertTrue(text.contains("桃花岛 | ID: A703 | 西溪园区A楼7F"));
        assertTrue(text.contains("聚贤庄 | ID: B305 | 西溪园区B楼3F"));
        assertTrue(text.contains("百花谷 | ID: B308 | 西溪园区B楼3F"));
        assertTrue(text.contains("侠客岛 | ID: C502 | 紫金港园区C楼5F"));
        assertTrue(text.contains("黑木崖 | ID: C509 | 紫金港园区C楼5F"));
        assertFalse(text.contains("园区 A 楼"));
        assertFalse(text.contains("园区 B 楼"));
        assertFalse(text.contains("园区 C 楼"));
    }

    @Test
    void shouldShowRoomNameAndIdInBookingReceipt() {
        CallToolResult result = executor.meetingRoomBookToolSpecification().callHandler().apply(null,
                request("meeting_room_book", Map.of(
                        "roomId", "A701",
                        "date", "2026-09-07",
                        "startTime", "20:00",
                        "endTime", "21:00",
                        "topic", "需求评审")));

        assertFalse(result.isError());
        assertTrue(text(result).contains("会议室: 光明顶（ID: A701，西溪园区A楼7F"));
    }

    private static CallToolRequest request(String name, Map<String, Object> arguments) {
        return new CallToolRequest(name, arguments);
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }
}

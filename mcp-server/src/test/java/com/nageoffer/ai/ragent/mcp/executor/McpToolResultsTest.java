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

package com.nageoffer.ai.ragent.mcp.executor;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolResultsTest {

    private static final String TOOL_ID = "query_order";

    /**
     * 键名带反向 DNS 前缀是规范要求，且必须与调用方那份常量逐字符一致，改一侧就断
     */
    @Test
    void userIdKeyMustStayInSyncWithCaller() {
        assertEquals("com.nageoffer.ragent/userId", McpToolResults.META_USER_ID);
    }

    @Test
    void shouldReadUserIdFromMeta() {
        assertEquals("2001", McpToolResults.userId(request(Map.of(McpToolResults.META_USER_ID, "2001"))));
    }

    /**
     * 取不到一律 null：没带、带了空串、带了个非字符串、键名写成裸 userId，对用户态工具是同一件事
     */
    @Test
    void shouldReturnNullWhenIdentityIsUnusable() {
        assertNull(McpToolResults.userId(request(null)));
        assertNull(McpToolResults.userId(request(Map.of())));
        assertNull(McpToolResults.userId(request(Map.of(McpToolResults.META_USER_ID, "   "))));
        assertNull(McpToolResults.userId(request(Map.of(McpToolResults.META_USER_ID, 2001))));
        assertNull(McpToolResults.userId(request(Map.of("userId", "2001"))));
        assertNull(McpToolResults.userId(null));
    }

    /**
     * 回绝要走 isError，否则模型会把这句话当正常业务结论转述成「你没有订单」
     */
    @Test
    void identityRequiredMustBeAnError() {
        CallToolResult result = McpToolResults.identityRequired(TOOL_ID);

        assertTrue(result.isError());
        assertTrue(((TextContent) result.content().get(0)).text().contains("身份"));
    }

    /**
     * 不许把这句话说成「请重新登录」：用户本来就在登录态，断的是调用链，重登治不好，只会把排查引到死路上
     */
    @Test
    void identityRequiredMustNotBlameTheUser() {
        String text = ((TextContent) McpToolResults.identityRequired(TOOL_ID).content().get(0)).text();

        assertFalse(text.contains("登录"));
    }

    /**
     * 底层异常的原文一个字都不许进返回值：返回值是给模型看的，模型会把 SQL 片段、连接串
     * 当业务结论转述给用户
     */
    @Test
    void failureMustNotLeakUnderlyingMessage() {
        String secret = "ERROR: relation \"t_order\" does not exist, jdbc:postgresql://10.0.0.7:5432/bit";

        CallToolResult result = McpToolResults.failure("订单查询", new IllegalStateException(secret));

        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(result.isError());
        assertTrue(text.contains("订单查询失败"));
        assertFalse(text.contains("t_order"));
        assertFalse(text.contains("10.0.0.7"));
    }

    /**
     * 空 message、异常自身的类名也算原文：{@code getMessage()} 为 null 时不许退化成把 toString 拼进去
     */
    @Test
    void failureMustNotLeakExceptionType() {
        String text = ((TextContent) McpToolResults.failure("下单", new NullPointerException())
                .content().get(0)).text();

        assertFalse(text.contains("NullPointerException"));
        assertFalse(text.contains("null"));
    }

    /**
     * {@link McpToolException} 是抛出方自己拼的文案，已声明可以给模型看——它带的状态码正是模型
     * 区分「限流待会再试」和「鉴权坏了别再试」的依据，脱掉就等于让模型盲猜
     */
    @Test
    void failureMustPassThroughDeclaredSafeMessage() {
        CallToolResult result = McpToolResults.failure("搜索", new McpToolException("上游返回异常状态码: 429"));

        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(result.isError());
        assertTrue(text.contains("429"));
    }

    private CallToolRequest request(Map<String, Object> meta) {
        return new CallToolRequest(TOOL_ID, new HashMap<>(), meta);
    }
}

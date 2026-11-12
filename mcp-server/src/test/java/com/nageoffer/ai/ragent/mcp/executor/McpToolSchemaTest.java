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

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.integer;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.number;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolSchemaTest {

    @Test
    void shouldCarryTypeDescriptionAndRequiredFlag() {
        JsonSchema schema = McpToolSchema.object()
                .required(string("roomId", "会议室ID"))
                .optional(integer("count", "条数"))
                .optional(number("amount", "金额"))
                .build();

        assertEquals("object", schema.type());
        assertEquals(List.of("roomId"), schema.required());
        assertEquals("string", property(schema, "roomId").get("type"));
        assertEquals("integer", property(schema, "count").get("type"));
        assertEquals("number", property(schema, "amount").get("type"));
        assertEquals("会议室ID", property(schema, "roomId").get("description"));
    }

    /**
     * 参数顺序必须落地就定死：{@code Map.copyOf} 按哈希排列，且排法受 JVM 启动时的随机 SALT 影响，
     * 于是发给模型的 schema 和确认卡的字段顺序每次重启都不一样
     * <p>
     * 键取到 16 个是为了让这条断言不靠运气：键少时哈希序碰巧等于声明序的概率不低，
     * 实测 {@code a..h} 就有整次 JVM 恰好保序，那样这条测试会在真有 bug 时照样绿
     */
    @Test
    void shouldPreserveDeclarationOrder() {
        List<String> declared = List.of("roomId", "date", "startTime", "endTime", "attendees",
                "query", "count", "freshness", "amount", "categoryAmounts", "status", "orderNo",
                "skuCode", "reason", "keyword", "pageSize");

        McpToolSchema builder = McpToolSchema.object();
        declared.forEach(name -> builder.optional(string(name, "描述" + name)));

        assertEquals(declared, List.copyOf(builder.build().properties().keySet()));
    }

    /**
     * 服务端不校验入参，宽松的 schema 等于把没声明的字段一路放进业务代码
     */
    @Test
    void shouldRejectUndeclaredProperties() {
        assertEquals(Boolean.FALSE, McpToolSchema.object()
                .required(string("id", "标识"))
                .build()
                .additionalProperties());
    }

    /**
     * 可选属性只在被调用时出现，不许给未声明的键塞 null——JSON Schema 里 {@code "default": null}
     * 和「没有默认值」是两件事，模型会把前者读成「默认不填」
     */
    @Test
    void shouldOmitUnsetAttributes() {
        Map<String, Object> plain = property(McpToolSchema.object()
                .optional(string("keyword", "关键词"))
                .build(), "keyword");

        assertFalse(plain.containsKey("title"));
        assertFalse(plain.containsKey("enum"));
        assertFalse(plain.containsKey("default"));
    }

    @Test
    void shouldCarryOptionalAttributes() {
        Map<String, Object> rich = property(McpToolSchema.object()
                .optional(string("status", "状态")
                        .title("持券状态")
                        .options(List.of("unused", "used"))
                        .defaultTo("unused"))
                .build(), "status");

        assertEquals("持券状态", rich.get("title"));
        assertEquals(List.of("unused", "used"), rich.get("enum"));
        assertEquals("unused", rich.get("default"));
    }

    /**
     * 重名会让后写的静默覆盖先写的，工具少一个参数还照常启动
     */
    @Test
    void shouldRejectDuplicateName() {
        McpToolSchema builder = McpToolSchema.object().required(string("id", "标识"));

        assertThrows(IllegalStateException.class, () -> builder.optional(integer("id", "另一个")));
    }

    /**
     * description 是模型判断该不该填这个参数的唯一依据，空着比不声明这个参数更糟
     */
    @Test
    void shouldRejectBlankNameOrDescription() {
        assertThrows(IllegalArgumentException.class, () -> string("id", "  "));
        assertThrows(IllegalArgumentException.class, () -> string("  ", "标识"));
    }

    /**
     * 构建产物要挡住后续改写：schema 是启动期建一次、全生命周期共享的单例，被改一次全局生效
     */
    @Test
    void shouldReturnImmutableSchema() {
        JsonSchema schema = McpToolSchema.object().required(string("id", "标识")).build();

        assertThrows(UnsupportedOperationException.class, () -> schema.properties().put("x", Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> schema.required().add("x"));
    }

    /**
     * 复用同一个 builder 继续声明，不许回头改到已经发出去的那份
     */
    @Test
    void buildShouldSnapshotCurrentState() {
        McpToolSchema builder = McpToolSchema.object().required(string("id", "标识"));
        JsonSchema first = builder.build();

        builder.optional(string("extra", "附加"));

        assertFalse(first.properties().containsKey("extra"));
        assertTrue(builder.build().properties().containsKey("extra"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> property(JsonSchema schema, String name) {
        return (Map<String, Object>) schema.properties().get(name);
    }
}

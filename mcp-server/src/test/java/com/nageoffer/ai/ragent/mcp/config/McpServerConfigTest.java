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

package com.nageoffer.ai.ragent.mcp.config;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpServerConfigTest {

    @Test
    void shouldAcceptToolsThatDeclareReadOnlyHint() {
        assertThatCode(() -> McpServerConfig.requireReadOnlyHint(List.of(
                spec("read_query", McpToolAnnotations.READ_ONLY),
                spec("write_submit", McpToolAnnotations.WRITE))))
                .doesNotThrowAnyException();
    }

    /**
     * 漏声明的是「不知道读写」，不是「只读」，放过去等于把确认与否交给运气
     */
    @Test
    void shouldRejectToolsMissingReadOnlyHint() {
        assertThatThrownBy(() -> McpServerConfig.requireReadOnlyHint(List.of(
                spec("no_annotation", null),
                spec("blank_hint", new ToolAnnotations(null, null, null, null, null, null)),
                spec("declared", new ToolAnnotations(null, true, false, true, false, null)))))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("no_annotation", "blank_hint")
                        .doesNotContain("declared"));
    }

    private McpServerFeatures.SyncToolSpecification spec(String name, ToolAnnotations annotations) {
        Tool tool = Tool.builder()
                .name(name)
                .description("测试工具")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), false, null, null))
                .annotations(annotations)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> null)
                .build();
    }
}

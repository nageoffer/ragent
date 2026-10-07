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

package com.nageoffer.ai.ragent.agent.memory;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContextTrimmerTest {

    private static final String SEARCH = "search_knowledge";
    private static final String SEARCH_OUTPUT = "知识".repeat(200);

    private AgentMemoryProperties memoryProperties;
    private AgentContextTrimmer trimmer;

    @BeforeEach
    void setUp() {
        memoryProperties = new AgentMemoryProperties();
        memoryProperties.setContextWindowChars(8_000);
        trimmer = new AgentContextTrimmer(memoryProperties);
    }

    @Test
    void shouldTrimSmallSavingsAndContinueWhenAnotherCycleLeavesProtection() {
        List<Msg> context = new ArrayList<>();
        context.add(userMessage("历史".repeat(2_500)));
        Msg oldest = addCycle(context, "oldest", SEARCH, SEARCH_OUTPUT);
        Msg next = addCycle(context, "next", SEARCH, SEARCH_OUTPUT);
        Msg recent = addCycle(context, "recent", SEARCH, SEARCH_OUTPUT);
        context.add(userMessage("继续查询"));
        List<Msg> before = List.copyOf(context);
        int beforeChars = AgentContextChars.total(context);

        AgentContextTrimmer.TrimResult first = trimmer.trimInPlace(context);

        assertThat(beforeChars).isGreaterThan(memoryProperties.resolveTrimTriggerChars());
        assertThat(first.changed()).isTrue();
        assertThat(first.reclaimedChars()).isPositive().isLessThan(beforeChars / 5);
        assertThat(first.reclaimedChars()).isEqualTo(beforeChars - AgentContextChars.total(context));
        assertThat(first.replacements()).containsOnlyKeys(oldest);
        Msg placeholder = first.replacements().get(oldest);
        assertThat(context).hasSize(before.size());
        assertThat(context.get(2)).isSameAs(placeholder);
        assertThat(context.get(1)).isSameAs(before.get(1));
        assertThat(context.get(4)).isSameAs(next);
        assertThat(context.get(6)).isSameAs(recent);
        ToolResultBlock trimmed = resultBlock(placeholder);
        ToolResultBlock original = resultBlock(oldest);
        assertThat(AgentContextTrimmer.isEvicted(trimmed)).isTrue();
        assertThat(trimmed.getId()).isEqualTo(original.getId());
        assertThat(trimmed.getName()).isEqualTo(original.getName());
        assertThat(trimmed.getState()).isEqualTo(original.getState());
        assertThat(trimmed.getMetadata()).isEqualTo(original.getMetadata());

        Msg newest = addCycle(context, "newest", SEARCH, SEARCH_OUTPUT);
        context.add(userMessage("再查一次"));
        int secondBeforeChars = AgentContextChars.total(context);

        AgentContextTrimmer.TrimResult second = trimmer.trimInPlace(context);

        assertThat(second.changed()).isTrue();
        assertThat(second.replacements()).containsOnlyKeys(next);
        assertThat(second.reclaimedChars()).isPositive().isLessThan(secondBeforeChars / 5);
        assertThat(second.reclaimedChars()).isEqualTo(secondBeforeChars - AgentContextChars.total(context));
        assertThat(AgentContextTrimmer.isEvicted(resultBlock(context.get(4)))).isTrue();
        assertThat(context.get(2)).isSameAs(placeholder);
        assertThat(context.get(6)).isSameAs(recent);
        assertThat(context.get(9)).isSameAs(newest);
        assertThat(trimmer.trimInPlace(context)).isSameAs(AgentContextTrimmer.TrimResult.UNCHANGED);
    }

    @Test
    void shouldLeaveContextUnchangedWhenAllResultsAreProtectedOrIneligible() {
        List<Msg> context = new ArrayList<>();
        context.add(userMessage("历史".repeat(2_500)));
        context.add(Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(List.of(toolUse("completed", SEARCH), toolUse("pending", SEARCH))).build());
        context.add(toolResult("completed", SEARCH, SEARCH_OUTPUT));
        addCycle(context, "non-evictable", "submit_order", SEARCH_OUTPUT);
        addCycle(context, "already-short", SEARCH, "无结果");
        addCycle(context, "recent-one", SEARCH, SEARCH_OUTPUT);
        addCycle(context, "recent-two", SEARCH, SEARCH_OUTPUT);
        context.add(userMessage("本轮查询"));
        addCycle(context, "current-turn", SEARCH, SEARCH_OUTPUT);
        List<Msg> before = List.copyOf(context);

        assertThat(AgentContextChars.total(context)).isGreaterThan(memoryProperties.resolveTrimTriggerChars());
        assertThat(trimmer.trimInPlace(context)).isSameAs(AgentContextTrimmer.TrimResult.UNCHANGED);
        assertThat(context).containsExactlyElementsOf(before);
    }

    @Test
    void shouldKeepEligibleResultsBelowTrimTrigger() {
        List<Msg> context = new ArrayList<>();
        context.add(userMessage("查询知识库"));
        addCycle(context, "oldest", SEARCH, SEARCH_OUTPUT);
        addCycle(context, "recent-one", SEARCH, SEARCH_OUTPUT);
        addCycle(context, "recent-two", SEARCH, SEARCH_OUTPUT);
        context.add(userMessage("继续"));
        List<Msg> before = List.copyOf(context);

        assertThat(AgentContextChars.total(context)).isLessThan(memoryProperties.resolveTrimTriggerChars());
        assertThat(trimmer.trimInPlace(context)).isSameAs(AgentContextTrimmer.TrimResult.UNCHANGED);
        assertThat(context).containsExactlyElementsOf(before);
    }

    @Test
    void shouldReturnUnchangedForEmptyContext() {
        assertThat(trimmer.trimInPlace(new ArrayList<>())).isSameAs(AgentContextTrimmer.TrimResult.UNCHANGED);
    }

    @Test
    void shouldKeepEmptyInputInPlaceholderWhenToolUseHasNoInput() {
        List<Msg> context = new ArrayList<>();
        context.add(userMessage("历史".repeat(2_500)));
        context.add(Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(ToolUseBlock.builder().id("no-input").name(SEARCH).build()).build());
        Msg oldest = toolResult("no-input", SEARCH, SEARCH_OUTPUT);
        context.add(oldest);
        addCycle(context, "recent-one", SEARCH, SEARCH_OUTPUT);
        addCycle(context, "recent-two", SEARCH, SEARCH_OUTPUT);
        context.add(userMessage("继续查询"));

        AgentContextTrimmer.TrimResult result = trimmer.trimInPlace(context);

        assertThat(result.replacements()).containsOnlyKeys(oldest);
        String placeholder = ((TextBlock) resultBlock(context.get(2)).getOutput().get(0)).getText();
        assertThat(placeholder).isEqualTo("[历史工具结果已省略，原长 " + SEARCH_OUTPUT.length() + " 字符，原入参 {}]");
    }

    private static Msg userMessage(String text) {
        return Msg.builder().name("user").role(MsgRole.USER).textContent(text).build();
    }

    private static Msg addCycle(List<Msg> context, String id, String toolName, String output) {
        context.add(Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(toolUse(id, toolName)).build());
        Msg result = toolResult(id, toolName, output);
        context.add(result);
        return result;
    }

    private static ToolUseBlock toolUse(String id, String toolName) {
        return ToolUseBlock.builder().id(id).name(toolName).input(Map.of("query", id)).build();
    }

    private static Msg toolResult(String id, String toolName, String output) {
        return Msg.builder().name("tool").role(MsgRole.TOOL)
                .content(ToolResultBlock.builder().id(id).name(toolName)
                        .output(TextBlock.builder().text(output).build())
                        .state(ToolResultState.SUCCESS).metadata(Map.of("source", "test-knowledge"))
                        .build())
                .build();
    }

    private static ToolResultBlock resultBlock(Msg message) {
        return message.getContentBlocks(ToolResultBlock.class).get(0);
    }
}

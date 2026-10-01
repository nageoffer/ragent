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

package com.nageoffer.ai.ragent.agent.tool;

import com.nageoffer.ai.ragent.agent.memory.AgentMemoryItem;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome.Status;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemorySnapshot;
import com.nageoffer.ai.ragent.agent.memory.AgentUserMemoryMiddleware;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceContextKeys;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MemoryFlushToolTest {

    private static final String USER_ID = "2001";
    private static final String CONVERSATION_ID = "3001";
    private static final String REQUEST_ID = "4001";

    private AgentMemoryPipeline memoryPipeline;
    private MemoryFlushTool tool;

    @BeforeEach
    void setUp() {
        memoryPipeline = mock(AgentMemoryPipeline.class);
        tool = new MemoryFlushTool("记忆整理工具描述", memoryPipeline);
    }

    @Test
    void shouldRefreshSnapshotAfterWrite() {
        AgentMemorySnapshot refreshed = new AgentMemorySnapshot(
                List.of(new AgentMemoryItem("m-1", "用户穿 XL 码")));
        when(memoryPipeline.flush(USER_ID, CONVERSATION_ID, REQUEST_ID))
                .thenReturn(new AgentMemoryOutcome(Status.WRITTEN, 2, 3, true));
        when(memoryPipeline.reloadSnapshot(USER_ID)).thenReturn(refreshed);
        RuntimeContext runtimeContext = newRuntimeContext();

        ToolResultBlock result = call(runtimeContext);

        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(textOf(result)).contains("2");
        // 同 call 内后续推理必须看到新视图，否则删掉的条目会在本轮继续注入
        assertThat((AgentMemorySnapshot) runtimeContext.get(AgentUserMemoryMiddleware.SNAPSHOT_KEY))
                .isEqualTo(refreshed);
    }

    /**
     * 库里那份没变就别重读，NOOP 也算判完，水位由管道推进
     */
    @Test
    void shouldReportSettledEmptyWithoutReloading() {
        when(memoryPipeline.flush(USER_ID, CONVERSATION_ID, REQUEST_ID))
                .thenReturn(new AgentMemoryOutcome(Status.SETTLED_EMPTY, 0, 3, false));
        RuntimeContext runtimeContext = newRuntimeContext();

        ToolResultBlock result = call(runtimeContext);

        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        verify(memoryPipeline, never()).reloadSnapshot(eq(USER_ID));
        assertThat((Object) runtimeContext.get(AgentUserMemoryMiddleware.SNAPSHOT_KEY)).isNull();
    }

    /**
     * 合并落了库而决策全灭：applied 为零但记忆集已变，本轮必须换新视图，文案也不能说「没整理出东西」
     */
    @Test
    void shouldRefreshSnapshotWhenSettledEmptyButMutated() {
        AgentMemorySnapshot refreshed = new AgentMemorySnapshot(
                List.of(new AgentMemoryItem("m-9", "合并后的条目")));
        when(memoryPipeline.flush(USER_ID, CONVERSATION_ID, REQUEST_ID))
                .thenReturn(new AgentMemoryOutcome(Status.SETTLED_EMPTY, 0, 3, true));
        when(memoryPipeline.reloadSnapshot(USER_ID)).thenReturn(refreshed);
        RuntimeContext runtimeContext = newRuntimeContext();

        ToolResultBlock result = call(runtimeContext);

        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(textOf(result)).contains("合并");
        assertThat((AgentMemorySnapshot) runtimeContext.get(AgentUserMemoryMiddleware.SNAPSHOT_KEY))
                .isEqualTo(refreshed);
    }

    /**
     * 没写进去的一律 ERROR：模型据此才知道不能向用户宣称已经记住
     * 文案还必须各不相同：光验 ERROR 标志的话，五个结局共用一句「整理失败」也能全绿
     */
    @Test
    void shouldReportFailureForEveryNonWritingOutcome() {
        List<Status> statuses = List.of(Status.BUSY, Status.CAPACITY_REJECTED, Status.CONFLICT,
                Status.FAILED, Status.DISABLED, Status.INCOMPLETE);
        Map<Status, String> texts = new LinkedHashMap<>();
        for (Status status : statuses) {
            when(memoryPipeline.flush(USER_ID, CONVERSATION_ID, REQUEST_ID))
                    .thenReturn(new AgentMemoryOutcome(status, 0, 3, false));

            ToolResultBlock result = call(newRuntimeContext());

            assertThat(result.getState()).as("状态 %s", status).isEqualTo(ToolResultState.ERROR);
            texts.put(status, textOf(result));
        }
        // 快照过期会自愈、容量满要人清理、开关关着是没开——同一句话让用户无从下手
        assertThat(texts.values()).as("逐结局文案 %s", texts).doesNotHaveDuplicates();
    }

    /**
     * 门槛结局只归后台批，管道在分叉处就把 flush 放过了，这里永远收不到
     * 真收到了要当缺陷炸出来落一条堆栈，而不是拿「后续会自动处理」把接错的线糊过去
     */
    @Test
    void shouldFailLoudlyOnBackgroundOnlyOutcome() {
        when(memoryPipeline.flush(USER_ID, CONVERSATION_ID, REQUEST_ID))
                .thenReturn(new AgentMemoryOutcome(Status.BELOW_THRESHOLD, 0, 1, false));

        ToolResultBlock result = call(newRuntimeContext());

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        // 走的是兜底那条异常分支，不是自己那句专属文案
        assertThat(textOf(result)).isEqualTo("记忆整理异常，本次内容未能写入");
    }

    @Test
    void shouldReportFailureWhenPipelineThrows() {
        when(memoryPipeline.flush(USER_ID, CONVERSATION_ID, REQUEST_ID))
                .thenThrow(new IllegalStateException("数据库连不上"));

        assertThat(call(newRuntimeContext()).getState()).isEqualTo(ToolResultState.ERROR);
    }

    @Test
    void shouldRejectCallWithoutIdentity() {
        ToolResultBlock result = call(RuntimeContext.builder().build());

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        verify(memoryPipeline, never()).flush(eq(USER_ID), eq(CONVERSATION_ID), eq(REQUEST_ID));
    }

    /**
     * 不知道本次请求是哪条消息，就判不了处理到了没有，硬跑只会把旧批次的完成报成本次成功
     */
    @Test
    void shouldRejectCallWithoutRequestMessage() {
        ToolResultBlock result = call(RuntimeContext.builder().userId(USER_ID).sessionId(CONVERSATION_ID).build());

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        verifyNoInteractions(memoryPipeline);
    }

    /**
     * 清空单列三种文案：普通「生效 N 条」会被模型说成清空了，原本就空也不能说成「没什么要记的」
     */
    @Test
    void shouldReportClearDistinctly() {
        Map<String, AgentMemoryOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put("只清空", new AgentMemoryOutcome(Status.WRITTEN, 0, 1, true, true, 3));
        outcomes.put("清空后新增", new AgentMemoryOutcome(Status.WRITTEN, 1, 1, true, true, 2));
        outcomes.put("原本就空", new AgentMemoryOutcome(Status.SETTLED_EMPTY, 0, 1, false, true, 0));
        when(memoryPipeline.reloadSnapshot(USER_ID)).thenReturn(AgentMemorySnapshot.empty());
        Map<String, String> texts = new LinkedHashMap<>();
        for (Map.Entry<String, AgentMemoryOutcome> entry : outcomes.entrySet()) {
            when(memoryPipeline.flush(USER_ID, CONVERSATION_ID, REQUEST_ID)).thenReturn(entry.getValue());

            ToolResultBlock result = call(newRuntimeContext());

            assertThat(result.getState()).as(entry.getKey()).isEqualTo(ToolResultState.SUCCESS);
            texts.put(entry.getKey(), textOf(result));
        }
        assertThat(texts.get("只清空")).contains("已清空全部长期记忆").contains("3");
        assertThat(texts.get("清空后新增")).contains("2").contains("生效了 1 条记忆变更");
        // 清空后的变更可能是后面批次的撤回，不许说成新记下
        assertThat(texts.values()).noneMatch(text -> text.contains("新记下"));
        assertThat(texts.get("原本就空")).contains("当前已无长期记忆");
        assertThat(texts.values()).as("逐结局文案 %s", texts).doesNotHaveDuplicates()
                .noneMatch(text -> text.startsWith("记忆已更新") || text.startsWith("本批对话"));
    }

    private RuntimeContext newRuntimeContext() {
        RuntimeContext runtimeContext = RuntimeContext.builder().userId(USER_ID).sessionId(CONVERSATION_ID).build();
        runtimeContext.put(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID, REQUEST_ID);
        return runtimeContext;
    }

    private ToolResultBlock call(RuntimeContext runtimeContext) {
        return tool.callAsync(ToolCallParam.builder()
                        .toolUseBlock(ToolUseBlock.builder()
                                .id("call-1")
                                .name(MemoryFlushTool.TOOL_NAME)
                                .input(Map.of())
                                .build())
                        .input(Map.of())
                        .runtimeContext(runtimeContext)
                        .build())
                .block();
    }

    private String textOf(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining());
    }
}

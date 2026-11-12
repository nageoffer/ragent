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
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryFlusher;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MemoryFlushToolTest {

    private static final String USER_ID = "2001";
    private static final String CONVERSATION_ID = "3001";
    private static final String REQUEST_ID = "4001";

    private AgentMemoryFlusher memoryFlusher;
    private MemoryFlushTool tool;

    @BeforeEach
    void setUp() {
        memoryFlusher = mock(AgentMemoryFlusher.class);
        tool = new MemoryFlushTool("记忆整理工具描述", memoryFlusher);
    }

    @Test
    void shouldRefreshSnapshotAfterWrite() {
        AgentMemorySnapshot refreshed = new AgentMemorySnapshot(
                List.of(new AgentMemoryItem("m-1", "用户穿 XL 码", null, null)));
        when(memoryFlusher.flush(USER_ID, REQUEST_ID))
                .thenReturn(new AgentMemoryOutcome(Status.COMMITTED, List.of("修改：用户穿 L 码 → 用户穿 XL 码"), ""));
        when(memoryFlusher.reloadSnapshot(USER_ID)).thenReturn(refreshed);
        RuntimeContext runtimeContext = newRuntimeContext();

        ToolResultBlock result = call(runtimeContext);

        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(textOf(result)).isEqualTo("整理完成，本次实际发生的记忆变更如下："
                + "\n- 修改：用户穿 L 码 → 用户穿 XL 码");
        // 同 call 内后续推理必须看到新视图，否则删掉的条目会在本轮继续注入
        assertThat((AgentMemorySnapshot) runtimeContext.get(AgentUserMemoryMiddleware.SNAPSHOT_KEY))
                .isEqualTo(refreshed);
    }

    /**
     * 用户说了两件事、只记下一件：文案只列落了库的那条，模型照着清单回报，不会把没记下的也说成记住了
     */
    @Test
    void shouldListOnlyWhatWasActuallySaved() {
        when(memoryFlusher.flush(USER_ID, REQUEST_ID))
                .thenReturn(new AgentMemoryOutcome(Status.COMMITTED, List.of("新增：用户戴眼镜"), ""));
        when(memoryFlusher.reloadSnapshot(USER_ID)).thenReturn(AgentMemorySnapshot.empty());

        String text = textOf(call(newRuntimeContext()));

        assertThat(text).contains("- 新增：用户戴眼镜").doesNotContain("出差");
    }

    /**
     * 这次判下来不用改，但别处可能刚改过记忆，本轮快照却是旧的：不管这次改没改库都要重读
     */
    @Test
    void shouldRefreshSnapshotEvenWhenNothingChanged() {
        AgentMemorySnapshot refreshed = new AgentMemorySnapshot(
                List.of(new AgentMemoryItem("m-1", "用户戴眼镜", null, null)));
        when(memoryFlusher.flush(USER_ID, REQUEST_ID))
                .thenReturn(new AgentMemoryOutcome(Status.COMMITTED, List.of(), "戴眼镜已经记着，无需重复新增"));
        when(memoryFlusher.reloadSnapshot(USER_ID)).thenReturn(refreshed);
        RuntimeContext runtimeContext = newRuntimeContext();

        ToolResultBlock result = call(runtimeContext);

        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat((AgentMemorySnapshot) runtimeContext.get(AgentUserMemoryMiddleware.SNAPSHOT_KEY))
                .isEqualTo(refreshed);
    }

    /**
     * 判下来没东西可记是正常结局：SUCCESS，只带实际状态与判定说明；怎么据此回答写在工具声明里，不在结果里重复
     */
    @Test
    void shouldReportEmptyCommitWithJudgeExplanation() {
        when(memoryFlusher.flush(USER_ID, REQUEST_ID)).thenReturn(new AgentMemoryOutcome(Status.COMMITTED, List.of(),
                "小屏手机偏好已经在有效记忆里，无需重复新增"));
        when(memoryFlusher.reloadSnapshot(USER_ID)).thenReturn(AgentMemorySnapshot.empty());

        ToolResultBlock result = call(newRuntimeContext());

        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(textOf(result)).isEqualTo("整理完成，这次没有产生记忆变更。"
                + "\n判定说明：小屏手机偏好已经在有效记忆里，无需重复新增");
    }

    /**
     * 本次请求没有确定写进去的一律 ERROR：模型据此才知道不能向用户宣称已经记住
     * 文案还必须各不相同：光验 ERROR 标志的话，几个结局共用一句「整理失败」也能全绿
     */
    @Test
    void shouldReportFailureForEveryNonWritingOutcome() {
        List<Status> statuses = List.of(Status.BUSY, Status.CAPACITY_EXCEEDED, Status.FAILED,
                Status.UNKNOWN, Status.DISABLED);
        Map<Status, String> texts = new LinkedHashMap<>();
        for (Status status : statuses) {
            when(memoryFlusher.flush(USER_ID, REQUEST_ID)).thenReturn(AgentMemoryOutcome.of(status));

            ToolResultBlock result = call(newRuntimeContext());

            assertThat(result.getState()).as("状态 %s", status).isEqualTo(ToolResultState.ERROR);
            texts.put(status, textOf(result));
        }
        // 正忙、容量满、失败、结果未知、开关关着各是一回事，同一句话让用户无从下手
        assertThat(texts.values()).as("逐结局文案 %s", texts).doesNotHaveDuplicates();
    }

    /**
     * 门槛结局只归后台批，管道在分叉处就把 flush 放过了，这里永远收不到
     * 真收到了要当缺陷炸出来落一条堆栈，而不是拿「后续会自动处理」把接错的线糊过去
     */
    @Test
    void shouldFailLoudlyOnBackgroundOnlyOutcome() {
        when(memoryFlusher.flush(USER_ID, REQUEST_ID))
                .thenReturn(AgentMemoryOutcome.of(Status.BELOW_THRESHOLD));

        ToolResultBlock result = call(newRuntimeContext());

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        // 走的是兜底那条异常分支，不是自己那句专属文案
        assertThat(textOf(result)).isEqualTo("记忆整理执行异常，无法确认本次内容是否已写入长期记忆。"
                + "请勿声称已经保存，也不要在本轮重复调用此工具。");
    }

    @Test
    void shouldReportFailureWhenPipelineThrows() {
        when(memoryFlusher.flush(USER_ID, REQUEST_ID))
                .thenThrow(new IllegalStateException("数据库连不上"));

        ToolResultBlock result = call(newRuntimeContext());

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(result)).isEqualTo("记忆整理执行异常，无法确认本次内容是否已写入长期记忆。"
                + "请勿声称已经保存，也不要在本轮重复调用此工具。");
    }

    @Test
    void shouldThrowWhenCallParamIsMissing() {
        assertThatThrownBy(() -> tool.callAsync(null).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("记忆整理未执行");
        verifyNoInteractions(memoryFlusher);
    }

    @Test
    void shouldThrowWhenToolUseBlockIsMissing() {
        ToolCallParam param = mock(ToolCallParam.class);

        assertThatThrownBy(() -> tool.callAsync(param).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("记忆整理未执行");
        verifyNoInteractions(memoryFlusher);
    }

    @Test
    void shouldThrowWhenRuntimeContextIsMissing() {
        assertThatThrownBy(() -> call(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("记忆整理未执行");
        verifyNoInteractions(memoryFlusher);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void shouldThrowWhenToolCallIdIsBlank(String toolCallId) {
        ToolUseBlock toolUseBlock = mock(ToolUseBlock.class);
        when(toolUseBlock.getId()).thenReturn(toolCallId);
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(toolUseBlock)
                .runtimeContext(newRuntimeContext())
                .build();

        assertThatThrownBy(() -> tool.callAsync(param).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("记忆整理未执行：当前会话的调用信息不完整，本次调用未写入或修改长期记忆。"
                        + "请如实告知用户本次未能保存记忆，不要声称已记住，也不要在本轮重复调用此工具。");
        verifyNoInteractions(memoryFlusher);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void shouldThrowWhenUserIdIsBlank(String userId) {
        RuntimeContext runtimeContext = mock(RuntimeContext.class);
        when(runtimeContext.getUserId()).thenReturn(userId);

        assertThatThrownBy(() -> call(runtimeContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("记忆整理未执行：当前会话的调用信息不完整，本次调用未写入或修改长期记忆。"
                        + "请如实告知用户本次未能保存记忆，不要声称已记住，也不要在本轮重复调用此工具。");
        verifyNoInteractions(memoryFlusher);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void shouldThrowWhenConversationIdIsBlank(String conversationId) {
        RuntimeContext runtimeContext = mock(RuntimeContext.class);
        when(runtimeContext.getUserId()).thenReturn(USER_ID);
        when(runtimeContext.getSessionId()).thenReturn(conversationId);

        assertThatThrownBy(() -> call(runtimeContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("记忆整理未执行：当前会话的调用信息不完整，本次调用未写入或修改长期记忆。"
                        + "请如实告知用户本次未能保存记忆，不要声称已记住，也不要在本轮重复调用此工具。");
        verifyNoInteractions(memoryFlusher);
    }

    /**
     * 不知道本次请求是哪条消息，就判不了处理到了没有，硬跑只会把旧批次的完成报成本次成功
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void shouldThrowWhenRequestMessageIdIsBlank(String requestMessageId) {
        RuntimeContext context = RuntimeContext.builder().userId(USER_ID).sessionId(CONVERSATION_ID).build();
        if (requestMessageId != null) {
            context.put(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID, requestMessageId);
        }

        assertThatThrownBy(() -> call(context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("记忆整理未执行：当前会话的调用信息不完整，本次调用未写入或修改长期记忆。"
                        + "请如实告知用户本次未能保存记忆，不要声称已记住，也不要在本轮重复调用此工具。");
        verifyNoInteractions(memoryFlusher);
    }

    @Test
    void shouldThrowWhenRequestMessageIdHasWrongType() {
        RuntimeContext context = newRuntimeContext();
        context.put(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID, 4001L);

        assertThatThrownBy(() -> call(context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("记忆整理未执行");
        verifyNoInteractions(memoryFlusher);
    }

    @Test
    void shouldPreserveSuccessfulOutcomeWhenSnapshotRefreshFails() {
        when(memoryFlusher.flush(USER_ID, REQUEST_ID))
                .thenReturn(new AgentMemoryOutcome(Status.COMMITTED, List.of("新增：用户戴眼镜"), ""));
        when(memoryFlusher.reloadSnapshot(USER_ID)).thenThrow(new IllegalStateException("快照读取失败"));
        RuntimeContext context = newRuntimeContext();
        AgentMemorySnapshot previous = AgentMemorySnapshot.empty();
        context.put(AgentUserMemoryMiddleware.SNAPSHOT_KEY, previous);

        ToolResultBlock result = call(context);

        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(textOf(result)).contains("整理完成", "新增：用户戴眼镜");
        assertThat((AgentMemorySnapshot) context.get(AgentUserMemoryMiddleware.SNAPSHOT_KEY)).isSameAs(previous);
    }

    /**
     * 清空的实际情况走变更清单：逐条列出删掉了什么，同句里要记的照常列出
     */
    @Test
    void shouldReportClearThroughChangeList() {
        when(memoryFlusher.flush(USER_ID, REQUEST_ID)).thenReturn(new AgentMemoryOutcome(Status.COMMITTED,
                List.of("删除：用户住在上海", "删除：用户喜欢红色", "新增：用户住在南京"), "清空之前的记忆，只留下常驻南京"));
        when(memoryFlusher.reloadSnapshot(USER_ID)).thenReturn(AgentMemorySnapshot.empty());

        String text = textOf(call(newRuntimeContext()));

        assertThat(text).contains("- 删除：用户住在上海", "- 删除：用户喜欢红色", "- 新增：用户住在南京",
                "判定说明：清空之前的记忆，只留下常驻南京");
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

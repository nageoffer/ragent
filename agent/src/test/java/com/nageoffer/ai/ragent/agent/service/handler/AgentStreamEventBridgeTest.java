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

package com.nageoffer.ai.ragent.agent.service.handler;

import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.dto.AgentCompletionPayload;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmField;
import com.nageoffer.ai.ragent.agent.dto.AgentMessageDelta;
import com.nageoffer.ai.ragent.agent.dto.AgentToolProgress;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.ResolvedCatalog;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentStreamEventBridgeTest {

    private static final String TASK_ID = "t-9001";
    private static final String CONVERSATION_ID = "c-2002";
    private static final String USER_ID = "u-1001";

    private SseEmitterSender sender;
    private StreamTaskManager taskManager;
    private AgentConversationService conversationService;
    private AgentStreamEventBridge bridge;

    @BeforeEach
    void setUp() {
        sender = mock(SseEmitterSender.class);
        taskManager = mock(StreamTaskManager.class);
        conversationService = mock(AgentConversationService.class);
        bridge = newBridge();
    }

    @Test
    void shouldUnregisterTaskWhenStreamCompletes() {
        bridge.onComplete();

        verify(taskManager).unregister(TASK_ID);
        verify(sender).complete();
    }

    @Test
    void shouldUnregisterTaskWhenStreamFails() {
        when(taskManager.isCancelled(TASK_ID)).thenReturn(false);

        bridge.onError(new IllegalStateException("上游炸了"));

        verify(taskManager).unregister(TASK_ID);
        verify(sender).complete();
    }

    @Test
    void shouldReportFailureInStreamInsteadOfClosingWithError() {
        when(taskManager.isCancelled(TASK_ID)).thenReturn(false);

        bridge.onError(new IllegalStateException("上游炸了"));

        ArgumentCaptor<String> events = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(sender, times(3)).sendEvent(events.capture(), payloads.capture());
        // 响应头早已是 text/event-stream，容器再写不出任何错误体，失败只能从流内出口
        assertThat(events.getAllValues()).containsExactly("message", "finish", "done");
        verify(sender, never()).fail(any());
        // finish 与落库同口径，前端定型的这条和刷新后拉到的是同一条
        assertThat(payloads.getAllValues().get(1))
                .isInstanceOfSatisfying(AgentCompletionPayload.class,
                        payload -> assertThat(payload.messageStatus()).isEqualTo("INTERRUPTED"));
    }

    @Test
    void shouldStreamInterruptNoticeAsBlock() {
        when(taskManager.isCancelled(TASK_ID)).thenReturn(false);
        bridge.onEvent(new TextBlockDeltaEvent("r-1", "b-1", "先说一句"));
        bridge.onEvent(new ToolCallStartEvent("r-1", "call-1", "leave_submit"));
        bridge.onEvent(new ToolResultEndEvent("r-1", "call-1", "leave_submit", ToolResultState.SUCCESS));

        bridge.onError(new IllegalStateException("上游炸了"));

        // 历史回放有块就不读 content，提示只塞进 content 的话刷新后这句就没了
        List<AgentBlock> blocks = capturedBlocks();
        AgentBlock notice = blocks.get(blocks.size() - 1);
        // 单开 error 块：混进 answer 就跟模型说的话一个身份，前端认不出这是失败
        assertThat(notice.getKind()).isEqualTo("error");
        // 工具没等到结果不代表没执行，一律提醒核对，别让用户直接再提交一遍
        assertThat(notice.getText()).contains("到对应业务系统核对");
        // 同一句也要当场流出去，否则这一屏和刷新后看到的不是同一条
        ArgumentCaptor<Object> deltas = ArgumentCaptor.forClass(Object.class);
        verify(sender, times(2)).sendEvent(eq("message"), deltas.capture());
        assertThat(deltas.getAllValues().get(1)).isEqualTo(new AgentMessageDelta("error", notice.getText()));
        // content 是不依赖块结构的完整文本留痕，正文与提示都得留在里面且分得开
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(conversationService).addAssistantMessage(
                any(), any(), content.capture(), any(), any(), any(), any());
        assertThat(content.getValue()).isEqualTo("先说一句\n\n" + notice.getText());
    }

    @Test
    void shouldUnregisterTaskWhenStreamCancelled() {
        bridge.finishCancelledStream();

        // 三条收尾路应对称，取消路不注销会把清理甩给 30 分钟 TTL
        verify(taskManager).unregister(TASK_ID);
        verify(sender).complete();
    }

    @Test
    void shouldSettleOnlyOnceAcrossExits() {
        bridge.finishCancelledStream();
        bridge.onComplete();
        bridge.onComplete();

        // 取消先落地后，正常完成路必须整条哑火，不得重复落库或重复注销
        verify(conversationService, never()).addAssistantMessage(
                any(), any(), any(), any(), any(), any(), any());
        verify(taskManager, times(1)).unregister(TASK_ID);
    }

    @Test
    void shouldKeepStreamAliveWhenToolCallIdMissing() {
        // 不规范的 OpenAI 兼容端点会回空 toolCallId，空键进 ConcurrentHashMap 直接打断整条事件流
        bridge.onEvent(new ToolCallStartEvent("r-1", null, "search_knowledge"));
        bridge.onEvent(new ToolResultTextDeltaEvent("r-1", null, "search_knowledge", "命中三条"));
        bridge.onEvent(new ToolResultEndEvent("r-1", null, "search_knowledge", ToolResultState.SUCCESS));
        bridge.onComplete();

        assertThat(capturedBlocks()).singleElement().satisfies(block -> {
            assertThat(block.getStatus()).isEqualTo("done");
            assertThat(block.getResult()).isEqualTo("命中三条");
        });
    }

    @Test
    void shouldSettleOpenTextBlockWhenStreamCancelled() {
        bridge.onEvent(new TextBlockDeltaEvent("r-1", "b-1", "前半"));
        bridge.onEvent(new TextBlockDeltaEvent("r-1", "b-1", "后半"));

        bridge.finishCancelledStream();

        // 增量攒在缓冲里，定格这一刻才落成 String，取消路同样不能丢
        assertThat(capturedBlocks()).singleElement().satisfies(block -> {
            assertThat(block.getKind()).isEqualTo("answer");
            assertThat(block.getText()).isEqualTo("前半后半");
        });
    }

    @Test
    void shouldSealTextBlockWhenToolStarts() {
        bridge.onEvent(new TextBlockDeltaEvent("r-1", "b-1", "先说一句"));
        bridge.onEvent(new ToolCallStartEvent("r-1", "call-1", "search_knowledge"));
        bridge.onEvent(new ToolResultEndEvent("r-1", "call-1", "search_knowledge", ToolResultState.SUCCESS));
        bridge.onEvent(new TextBlockDeltaEvent("r-1", "b-2", "再说一句"));
        bridge.onComplete();

        List<AgentBlock> blocks = capturedBlocks();
        assertThat(blocks).extracting(AgentBlock::getKind).containsExactly("answer", "tool", "answer");
        assertThat(blocks.get(0).getText()).isEqualTo("先说一句");
        assertThat(blocks.get(2).getText()).isEqualTo("再说一句");
    }

    @Test
    void shouldStampBlocksWithFullTimestamp() {
        bridge.onEvent(new TextBlockDeltaEvent("r-1", "b-1", "一句话"));
        bridge.onComplete();

        // 只存 HH:mm:ss，跨天会话回放时分不出这一行到底是哪天的
        String at = capturedBlocks().get(0).getAt();
        assertThat(LocalDateTime.parse(at).toLocalDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void shouldNotSendConfirmCardWhenPersistFails() {
        when(conversationService.addAssistantMessage(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("连接池打满"));

        bridge.onEvent(new ToolCallStartEvent("r-1", "call-1", "leave_submit"));
        bridge.onEvent(new RequireUserConfirmEvent("r-1", List.of(
                ToolUseBlock.builder().id("call-1").name("leave_submit").input(Map.of("day", "9-14")).build())));
        bridge.onComplete();

        // 卡片凭 messageId 续跑，没落库就没有这个凭据，发出去用户点了也结算不了
        verify(sender, never()).sendEvent(eq("confirm"), any());
        verify(sender).sendEvent(eq("finish"), any());
        verify(sender).sendEvent(eq("hint"), any());
    }

    /**
     * 前端靠 toolCallId 把结束事件配回开头那块，同名工具并发两次时按名字猜会配错
     */
    @Test
    void shouldCarryToolCallIdOnToolEvents() {
        bridge.onEvent(new ToolCallStartEvent("r-1", "call-7", "leave_submit"));
        bridge.onEvent(new ToolResultEndEvent("r-1", "call-7", "leave_submit", ToolResultState.SUCCESS));

        ArgumentCaptor<AgentToolProgress> captor = ArgumentCaptor.forClass(AgentToolProgress.class);
        verify(sender, times(2)).sendEvent(eq("tool"), captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AgentToolProgress::toolCallId, AgentToolProgress::status)
                .containsExactly(tuple("call-7", "start"), tuple("call-7", "end"));
    }

    @Test
    void shouldLabelConfirmArgumentsBySchemaDeclarationOrder() {
        AgentStreamEventBridge labeled = newBridge(leaveCatalog());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("reason", "回老家");
        input.put("leaveType", "年假");
        input.put("note", "模型多给的");

        labeled.onEvent(new RequireUserConfirmEvent("r-1", List.of(
                ToolUseBlock.builder().id("call-1").name("leave_submit").input(input).build())));
        labeled.onComplete();

        // 顺序跟 schema 走而不是跟模型输出走，标签只管展示，前端比对差异认的是 name
        // schema 里没声明的参数照样露出来，授权界面不能把参数漏在视野外
        assertThat(capturedBlocks().get(0).getCalls().get(0).getFields())
                .extracting(AgentConfirmField::getName, AgentConfirmField::getLabel, AgentConfirmField::getValue)
                .containsExactly(
                        tuple("leaveType", "假期类型", "年假"),
                        tuple("reason", "请假事由", "回老家"),
                        tuple("note", "note", "模型多给的"));
    }

    private ResolvedCatalog leaveCatalog() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("leaveType", Map.of("type", "string", "title", "假期类型"));
        properties.put("reason", Map.of("type", "string", "title", "请假事由"));
        Tool tool = Tool.builder()
                .name("leave_submit")
                .description("提交请假申请")
                .inputSchema(new JsonSchema("object", properties, List.of(), null, null, null))
                .build();
        McpToolExecutor executor = new McpToolExecutor() {
            @Override
            public Tool getToolDefinition() {
                return tool;
            }

            @Override
            public CallToolResult execute(Map<String, Object> parameters) {
                return null;
            }
        };
        return new ResolvedCatalog("知识库工具描述", null,
                List.of(new McpToolBinding("leave_submit", "请假申请", "提交请假申请", true, executor)),
                List.of(), List.of());
    }

    @SuppressWarnings("unchecked")
    private List<AgentBlock> capturedBlocks() {
        ArgumentCaptor<List<AgentBlock>> captor = ArgumentCaptor.forClass(List.class);
        verify(conversationService).addAssistantMessage(
                any(), any(), any(), any(), captor.capture(), any(), any());
        return captor.getValue();
    }

    private AgentStreamEventBridge newBridge() {
        return newBridge(new ResolvedCatalog("知识库工具描述", null, List.of(), List.of(), List.of()));
    }

    private AgentStreamEventBridge newBridge(ResolvedCatalog catalog) {
        return new AgentStreamEventBridge(AgentStreamEventBridge.Params.builder()
                .runHandle(new AgentRunHandle(TASK_ID, sender, taskManager))
                .conversationService(conversationService)
                .catalog(catalog)
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .title("会话标题")
                .replyToMessageId("m-3003")
                .build());
    }
}

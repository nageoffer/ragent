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
import com.nageoffer.ai.ragent.agent.dto.AgentTextBlockSeal;
import com.nageoffer.ai.ragent.agent.dto.AgentToolProgress;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.ResolvedCatalog;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients.RemoteTool;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolBatchFact;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentStreamEventBridgeTest {

    private static final String TASK_ID = "t-9001";
    private static final String CONVERSATION_ID = "c-2002";
    private static final String USER_ID = "u-1001";
    /** 模型侧 replyId */
    private static final String REASON_ID = "r-1";
    /** 执行侧 replyId */
    private static final String ACT_ID = "a-1";
    /** 首批批次号 */
    private static final String BATCH_ID = TASK_ID + "-0";

    private SseEmitterSender sender;
    private StreamTaskManager taskManager;
    private AgentConversationService conversationService;
    private MovableClock clock;
    private AgentToolExecutionFacts facts;
    private AgentStreamEventBridge bridge;

    @BeforeEach
    void setUp() {
        sender = mock(SseEmitterSender.class);
        taskManager = mock(StreamTaskManager.class);
        conversationService = mock(AgentConversationService.class);
        clock = new MovableClock();
        facts = new AgentToolExecutionFacts(TASK_ID, clock);
        bridge = newBridge();
    }

    @Test
    void shouldUnregisterTaskWhenStreamCompletes() {
        bridge.onComplete();

        verify(taskManager).unregister(TASK_ID);
        verify(sender).complete();
    }

    /**
     * SSE 与落库的耗时同源，各问一次时钟会不一致
     */
    @Test
    void shouldReportSameRunDurationToStreamAndStore() {
        when(conversationService.addAssistantMessage(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    clock.advance(120);
                    return "m-1";
                });
        clock.advance(4_200);

        bridge.onComplete();

        ArgumentCaptor<Long> persisted = ArgumentCaptor.forClass(Long.class);
        verify(conversationService).addAssistantMessage(
                any(), any(), any(), any(), any(), any(), any(), persisted.capture());
        assertThat(persisted.getValue()).isEqualTo(4_200L);
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(sender).sendEvent(eq("finish"), payloads.capture());
        assertThat(payloads.getValue())
                .isInstanceOfSatisfying(AgentCompletionPayload.class,
                        payload -> assertThat(payload.durationMs()).isEqualTo(4_200L));
    }

    /**
     * 文本块耗时由服务端量，避免浏览器掐表与刷新后不一致
     */
    @Test
    void shouldStampServerTimesOnTextBlocks() {
        bridge.onEvent(new ThinkingBlockDeltaEvent("r-1", "b-1", "先想一下"));
        clock.advance(2_100);
        // 换 kind 即封口上一段，思考那段的终点落在这一刻
        bridge.onEvent(new TextBlockDeltaEvent("r-1", "b-2", "答案是"));
        clock.advance(3_600);
        bridge.onComplete();

        List<AgentBlock> blocks = capturedBlocks();
        assertThat(blocks).extracting(AgentBlock::getKind).containsExactly("reasoning", "answer");
        assertThat(blocks).allSatisfy(block -> {
            assertThat(block.getStartedAt()).isNotNull();
            assertThat(block.getEndedAt()).isNotNull();
            // durationSource 只描述工具分批口径，文本块留空
            assertThat(block.getDurationSource()).isNull();
        });
        assertThat(blocks).extracting(AgentBlock::getDurationMs).containsExactly(2_100L, 3_600L);
        // 两段首尾相接，轴上无洞
        assertThat(blocks.get(1).getStartedAt()).isEqualTo(blocks.get(0).getEndedAt());
    }

    /**
     * 封口帧当场发出，保证直播与刷新后同源
     */
    @Test
    void shouldBroadcastSealedTextBlockTimes() {
        bridge.onEvent(new ThinkingBlockDeltaEvent("r-1", "b-1", "先想一下"));
        clock.advance(2_100);
        bridge.onEvent(new TextBlockDeltaEvent("r-1", "b-2", "答案是"));
        clock.advance(3_600);
        bridge.onComplete();

        ArgumentCaptor<Object> seals = ArgumentCaptor.forClass(Object.class);
        verify(sender, times(2)).sendEvent(eq("block"), seals.capture());
        List<AgentBlock> blocks = capturedBlocks();
        // 与落库同源
        assertThat(seals.getAllValues())
                .containsExactly(AgentTextBlockSeal.of(blocks.get(0)), AgentTextBlockSeal.of(blocks.get(1)));
        // 实时增量和历史块使用同一种内容类型，前端无需转换名称
        ArgumentCaptor<AgentMessageDelta> deltas = ArgumentCaptor.forClass(AgentMessageDelta.class);
        verify(sender, times(2)).sendEvent(eq("message"), deltas.capture());
        assertThat(deltas.getAllValues()).containsExactly(
                new AgentMessageDelta("reasoning", "先想一下"),
                new AgentMessageDelta("answer", "答案是"));
    }

    /**
     * 一次性补发没有流的过程，不应编造 0ms 的生成耗时
     */
    @Test
    void shouldLeaveTextTimesEmptyWhenNotStreamed() {
        bridge.onEvent(new AgentResultEvent(Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent("一次性给出的终答")
                .build()));

        bridge.onComplete();

        assertThat(capturedBlocks()).singleElement().satisfies(block -> {
            assertThat(block.getKind()).isEqualTo("answer");
            assertThat(block.getStartedAt()).isNull();
            assertThat(block.getEndedAt()).isNull();
            assertThat(block.getDurationMs()).isNull();
        });
        // 无起止则不发封口帧
        verify(sender, never()).sendEvent(eq("block"), any());
        verify(sender).sendEvent("message", new AgentMessageDelta("answer", "一次性给出的终答"));
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
                any(), any(), content.capture(), any(), any(), any(), any(), any());
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
                any(), any(), any(), any(), any(), any(), any(), any());
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
        // content 是正文全文，被工具块切成几段也要按序接回来，末段收尾前还没封口
        assertThat(capturedContent()).isEqualTo("先说一句再说一句");
        ArgumentCaptor<Object> updates = ArgumentCaptor.forClass(Object.class);
        verify(sender, times(4)).sendEvent(eq("block"), updates.capture());
        assertThat(updates.getAllValues()).containsExactly(
                capturedToolEvents().get(0), AgentTextBlockSeal.of(blocks.get(0)),
                capturedToolEvents().get(1), AgentTextBlockSeal.of(blocks.get(2)));
        assertThat(capturedToolEvents()).allSatisfy(update -> assertThat(update.kind()).isEqualTo("tool"));
        verify(sender, never()).sendEvent(eq("tool"), any());
    }

    /**
     * 思考与正文各走各的全文，分段规则一致
     */
    @Test
    void shouldPersistThinkingAcrossSegments() {
        bridge.onEvent(new ThinkingBlockDeltaEvent("r-1", "b-1", "先想一下"));
        bridge.onEvent(new ToolCallStartEvent("r-1", "call-1", "search_knowledge"));
        bridge.onEvent(new ToolResultEndEvent("r-1", "call-1", "search_knowledge", ToolResultState.SUCCESS));
        bridge.onEvent(new ThinkingBlockDeltaEvent("r-1", "b-2", "再想一下"));
        bridge.onComplete();

        assertThat(capturedBlocks()).extracting(AgentBlock::getKind)
                .containsExactly("reasoning", "tool", "reasoning");
        assertThat(capturedThinking()).isEqualTo("先想一下再想一下");
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
        when(conversationService.addAssistantMessage(any(), any(), any(), any(), any(), any(), any(), any()))
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
        bridge.onEvent(new ToolCallStartEvent(REASON_ID, "call-7", "leave_submit"));
        bridge.onEvent(new ToolResultStartEvent(ACT_ID, "call-7", "leave_submit"));
        bridge.onEvent(new ToolResultEndEvent(ACT_ID, "call-7", "leave_submit", ToolResultState.SUCCESS));

        // 三帧：pending / running / done
        assertThat(capturedToolEvents())
                .extracting(AgentToolProgress::toolCallId, AgentToolProgress::status, AgentToolProgress::ok)
                .containsExactly(
                        tuple("call-7", "pending", null),
                        tuple("call-7", "running", null),
                        tuple("call-7", "done", true));
    }

    /**
     * 耗时从进入工具体起算，不含模型吐参数阶段
     */
    @Test
    void shouldStartTimingAtExecutionNotAtModelOutput() {
        bridge.onEvent(new ToolCallStartEvent(REASON_ID, "call-1", "leave_submit"));
        // 吐参数 5 秒属于推理，不计入工具耗时
        clock.advance(5_000);
        ToolBatchFact batch = beginBatch("call-1");
        bridge.onEvent(new ToolResultStartEvent(ACT_ID, "call-1", "leave_submit"));
        long executionStart = facts.markStarted("call-1");
        clock.advance(7);
        facts.markEnded("call-1");
        bridge.onEvent(new ToolResultEndEvent(ACT_ID, "call-1", "leave_submit", ToolResultState.SUCCESS));
        facts.endBatch(batch);
        bridge.onComplete();

        assertThat(capturedBlocks()).singleElement().satisfies(block -> {
            assertThat(block.getStartedAt()).isEqualTo(executionStart);
            assertThat(block.getDurationMs()).isEqualTo(7);
            // durationSource 区分逐条口径与老数据整批口径
            assertThat(block.getDurationSource()).isEqualTo(AgentBlock.DURATION_SOURCE_TOOL);
        });
    }

    /**
     * 并行工具各自独立计时，不共用整批区间
     */
    @Test
    void shouldGiveEachParallelToolItsOwnExecutionWindow() {
        bridge.onEvent(new ToolCallStartEvent(REASON_ID, "call-1", "search_knowledge"));
        bridge.onEvent(new ToolCallStartEvent(REASON_ID, "call-2", "leave_submit"));
        ToolBatchFact batch = beginBatch("call-1", "call-2");
        bridge.onEvent(new ToolResultStartEvent(ACT_ID, "call-1", "search_knowledge"));
        bridge.onEvent(new ToolResultStartEvent(ACT_ID, "call-2", "leave_submit"));
        // 两条真的在并行：先后进体，区间重叠，一条 12 毫秒一条 30 毫秒
        facts.markStarted("call-1");
        clock.advance(2);
        facts.markStarted("call-2");
        clock.advance(10);
        facts.markEnded("call-1");
        clock.advance(20);
        facts.markEnded("call-2");
        bridge.onEvent(new ToolResultEndEvent(ACT_ID, "call-1", "search_knowledge", ToolResultState.SUCCESS));
        // 事件间隔是框架发送耗时，不是工具执行差
        clock.advance(3);
        bridge.onEvent(new ToolResultEndEvent(ACT_ID, "call-2", "leave_submit", ToolResultState.SUCCESS));
        facts.endBatch(batch);
        bridge.onComplete();

        List<AgentBlock> blocks = capturedBlocks();
        assertThat(blocks).extracting(AgentBlock::getBatchId).containsExactly(BATCH_ID, BATCH_ID);
        assertThat(blocks).extracting(AgentBlock::getDurationMs).containsExactly(12L, 30L);
        // 区间重叠，并行而非串行
        assertThat(blocks.get(1).getStartedAt()).isLessThan(blocks.get(0).getEndedAt());
        // 同名并行靠 callIndex 区分
        assertThat(blocks).extracting(AgentBlock::getCallIndex).containsExactly(0, 1);
    }

    /**
     * 同名并行按 toolCallId 配对，按名字会配错
     */
    @Test
    void shouldPairSameNameParallelCallsById() {
        bridge.onEvent(new ToolCallStartEvent(REASON_ID, "call-1", "leave_submit"));
        bridge.onEvent(new ToolCallStartEvent(REASON_ID, "call-2", "leave_submit"));
        bridge.onEvent(new ToolResultStartEvent(ACT_ID, "call-1", "leave_submit"));
        bridge.onEvent(new ToolResultStartEvent(ACT_ID, "call-2", "leave_submit"));
        bridge.onEvent(new ToolResultTextDeltaEvent(ACT_ID, "call-2", "leave_submit", "第二单已提交"));
        bridge.onEvent(new ToolResultTextDeltaEvent(ACT_ID, "call-1", "leave_submit", "第一单已提交"));
        bridge.onEvent(new ToolResultEndEvent(ACT_ID, "call-2", "leave_submit", ToolResultState.SUCCESS));
        bridge.onEvent(new ToolResultEndEvent(ACT_ID, "call-1", "leave_submit", ToolResultState.ERROR));
        bridge.onComplete();

        // 结束顺序与调用顺序相反，按 id 配对才不会串
        assertThat(capturedBlocks())
                .extracting(AgentBlock::getToolCallId, AgentBlock::getCallIndex,
                        AgentBlock::getStatus, AgentBlock::getResult)
                .containsExactly(
                        tuple("call-1", 0, "failed", "第一单已提交"),
                        tuple("call-2", 1, "done", "第二单已提交"));
    }

    /**
     * SSE 终态帧与落库同源
     */
    @Test
    void shouldSendSameCorrelationFieldsAsPersisted() {
        bridge.onEvent(new ToolCallStartEvent(REASON_ID, "call-1", "leave_submit"));
        clock.advance(4_000);
        ToolBatchFact batch = beginBatch("call-1");
        bridge.onEvent(new ToolResultStartEvent(ACT_ID, "call-1", "leave_submit"));
        facts.markStarted("call-1");
        clock.advance(9);
        facts.markEnded("call-1");
        bridge.onEvent(new ToolResultEndEvent(ACT_ID, "call-1", "leave_submit", ToolResultState.SUCCESS));
        facts.endBatch(batch);
        bridge.onComplete();

        AgentBlock block = capturedBlocks().get(0);
        AgentToolProgress last = capturedToolEvents().get(capturedToolEvents().size() - 1);
        assertThat(last).isEqualTo(new AgentToolProgress("tool", block.getToolCallId(), block.getName(),
                block.getDisplayName(), block.getStatus(), block.getResult(), true, block.getAt(),
                block.getBatchId(), block.getCallIndex(), block.getStartedAt(), block.getEndedAt(),
                block.getDurationMs(), block.getDurationSource()));
    }

    /**
     * 确认卡一挂整批推 awaiting，包括卡片没点名的同批工具
     */
    @Test
    void shouldHoldWholeBatchAwaitingWhenConfirmRequired() {
        bridge.onEvent(new ToolCallStartEvent(REASON_ID, "call-1", "leave_submit"));
        bridge.onEvent(new ToolCallStartEvent(REASON_ID, "call-2", "search_knowledge"));
        // 事件只带需要裁决的那条，另一条同批工具也没有执行
        bridge.onEvent(new RequireUserConfirmEvent(REASON_ID, List.of(
                ToolUseBlock.builder().id("call-1").name("leave_submit").input(Map.of("day", "9-14")).build())));
        bridge.onComplete();

        assertThat(capturedBlocks())
                .filteredOn(block -> "tool".equals(block.getKind()))
                .allSatisfy(block -> {
                    // awaiting 而非 interrupted
                    assertThat(block.getStatus()).isEqualTo("awaiting");
                    assertThat(block.getBatchId()).isNull();
                    assertThat(block.getStartedAt()).isNull();
                    assertThat(block.getEndedAt()).isNull();
                });
    }

    /**
     * 用户点取消，框架不发工具事件，块由 AllToolsDenied 补建
     */
    @Test
    void shouldMarkDeniedWithoutFabricatingExecution() {
        bridge.onEvent(new AllToolsDeniedEvent(List.of(
                ToolUseBlock.builder().id("call-1").name("leave_submit").input(Map.of("day", "9-14")).build())));
        bridge.onComplete();

        assertThat(capturedBlocks()).singleElement().satisfies(block -> {
            assertThat(block.getStatus()).isEqualTo("denied");
            // 没执行过，无批次无起止
            assertThat(block.getBatchId()).isNull();
            assertThat(block.getStartedAt()).isNull();
            assertThat(block.getDurationMs()).isNull();
        });
        assertThat(capturedToolEvents()).singleElement()
                .extracting(AgentToolProgress::status, AgentToolProgress::ok)
                .containsExactly("denied", false);
    }

    /**
     * 规则拒绝的工具归批但无起止，不编造 0ms 耗时
     */
    @Test
    void shouldNotGiveRuleDeniedToolAnExecutionWindow() {
        bridge.onEvent(new ToolCallStartEvent(REASON_ID, "call-1", "leave_submit"));
        bridge.onEvent(new ToolCallStartEvent(REASON_ID, "call-2", "search_knowledge"));
        ToolBatchFact batch = beginBatch("call-1", "call-2");
        // 框架先把规则拒绝的那条从头到尾走完，再开始执行放行的
        bridge.onEvent(new ToolResultStartEvent(ACT_ID, "call-1", "leave_submit"));
        facts.markShortCircuit("call-1", AgentToolExecutionFacts.SHORT_CIRCUIT_PRE_EXECUTION, null);
        bridge.onEvent(new ToolResultEndEvent(ACT_ID, "call-1", "leave_submit", ToolResultState.DENIED));
        bridge.onEvent(new ToolResultStartEvent(ACT_ID, "call-2", "search_knowledge"));
        facts.markStarted("call-2");
        clock.advance(20);
        facts.markEnded("call-2");
        bridge.onEvent(new ToolResultEndEvent(ACT_ID, "call-2", "search_knowledge", ToolResultState.SUCCESS));
        facts.endBatch(batch);
        bridge.onComplete();

        List<AgentBlock> blocks = capturedBlocks();
        // 同批
        assertThat(blocks).extracting(AgentBlock::getBatchId).containsExactly(BATCH_ID, BATCH_ID);
        assertThat(blocks).extracting(AgentBlock::getStatus).containsExactly("denied", "done");
        // 被拒那条无耗时，放行那条 20ms
        assertThat(blocks).extracting(AgentBlock::getDurationMs).containsExactly(null, 20L);
        assertThat(blocks.get(0).getStartedAt()).isNull();
    }

    /**
     * 续跑没有 ToolCallStart，从 ToolResultStart 补建块
     */
    @Test
    void shouldOpenBlockWhenResumedRunSkipsCallStart() {
        ToolBatchFact batch = beginBatch("call-1");
        bridge.onEvent(new ToolResultStartEvent(ACT_ID, "call-1", "leave_submit"));
        facts.markStarted("call-1");
        clock.advance(15);
        facts.markEnded("call-1");
        bridge.onEvent(new ToolResultTextDeltaEvent(ACT_ID, "call-1", "leave_submit", "已提交"));
        bridge.onEvent(new ToolResultEndEvent(ACT_ID, "call-1", "leave_submit", ToolResultState.SUCCESS));
        facts.endBatch(batch);
        bridge.onComplete();

        assertThat(capturedBlocks()).singleElement().satisfies(block -> {
            assertThat(block.getStatus()).isEqualTo("done");
            assertThat(block.getResult()).isEqualTo("已提交");
            // 序号由 acting 名册补
            assertThat(block.getCallIndex()).isZero();
            assertThat(block.getDurationMs()).isEqualTo(15);
        });
    }

    /**
     * 断流时有起点无终点，不补造 endedAt
     */
    @Test
    void shouldNotFabricateEndTimeWhenBatchIsCutOff() {
        bridge.onEvent(new ToolCallStartEvent(REASON_ID, "call-1", "leave_submit"));
        beginBatch("call-1");
        bridge.onEvent(new ToolResultStartEvent(ACT_ID, "call-1", "leave_submit"));
        long executionStart = facts.markStarted("call-1");
        clock.advance(30);

        bridge.finishCancelledStream();

        assertThat(capturedBlocks()).singleElement().satisfies(block -> {
            assertThat(block.getStatus()).isEqualTo("interrupted");
            assertThat(block.getStartedAt()).isEqualTo(executionStart);
            // 没跑完就没有终点
            assertThat(block.getEndedAt()).isNull();
            assertThat(block.getDurationMs()).isNull();
        });
    }

    /**
     * pending 阶段断流，无执行区间
     */
    @Test
    void shouldMarkPendingToolInterruptedWithoutExecutionWindow() {
        bridge.onEvent(new ToolCallStartEvent(REASON_ID, "call-1", "leave_submit"));

        bridge.finishCancelledStream();

        assertThat(capturedBlocks()).singleElement().satisfies(block -> {
            assertThat(block.getStatus()).isEqualTo("interrupted");
            assertThat(block.getStartedAt()).isNull();
            assertThat(block.getEndedAt()).isNull();
        });
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
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn("default");
        return new ResolvedCatalog("知识库工具描述", null,
                List.of(McpToolBinding.of("leave_submit", "请假申请", "提交请假申请", true,
                        new RemoteTool(tool, client))),
                List.of(), List.of());
    }

    @SuppressWarnings("unchecked")
    private List<AgentBlock> capturedBlocks() {
        ArgumentCaptor<List<AgentBlock>> captor = ArgumentCaptor.forClass(List.class);
        verify(conversationService).addAssistantMessage(
                any(), any(), any(), any(), captor.capture(), any(), any(), any());
        return captor.getValue();
    }

    private String capturedContent() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(conversationService).addAssistantMessage(
                any(), any(), captor.capture(), any(), any(), any(), any(), any());
        return captor.getValue();
    }

    private String capturedThinking() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(conversationService).addAssistantMessage(
                any(), any(), any(), captor.capture(), any(), any(), any(), any());
        return captor.getValue();
    }

    private List<AgentToolProgress> capturedToolEvents() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(sender, atLeastOnce()).sendEvent(eq("block"), captor.capture());
        return captor.getAllValues().stream()
                .filter(AgentToolProgress.class::isInstance)
                .map(AgentToolProgress.class::cast)
                .toList();
    }

    private AgentStreamEventBridge newBridge() {
        return newBridge(new ResolvedCatalog("知识库工具描述", null, List.of(), List.of(), List.of()));
    }

    private AgentStreamEventBridge newBridge(ResolvedCatalog catalog) {
        return AgentStreamEventBridge.builder()
                .runHandle(new AgentRunHandle(TASK_ID, sender, taskManager, facts, null))
                .conversationService(conversationService)
                .catalog(catalog)
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .title("会话标题")
                .replyToMessageId("m-3003")
                .clock(clock)
                .facts(facts)
                .build();
    }

    /**
     * 模拟 AgentToolBatchMiddleware 开合批次
     */
    private ToolBatchFact beginBatch(String... toolCallIds) {
        ToolBatchFact batch = facts.beginBatch();
        facts.enroll(batch, List.of(toolCallIds));
        return batch;
    }

    /**
     * 手动推进的时钟，起点取真实时刻以保证日期戳可比
     */
    private static final class MovableClock extends Clock {

        private Instant instant = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(long millis) {
            instant = instant.plusMillis(millis);
        }
    }
}

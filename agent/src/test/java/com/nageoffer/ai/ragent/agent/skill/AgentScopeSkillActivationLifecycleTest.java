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

package com.nageoffer.ai.ragent.agent.skill;

import com.nageoffer.ai.ragent.agent.dao.mapper.AgentContextCompactionMapper;
import com.nageoffer.ai.ragent.agent.memory.AgentContextCompactionMiddleware;
import com.nageoffer.ai.ragent.agent.memory.AgentContextCompactor;
import com.nageoffer.ai.ragent.agent.memory.AgentContextTrimmer;
import com.nageoffer.ai.ragent.agent.memory.AgentConversationSummarizer;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 复现 AgentScope 原生 Skill 的激活周期：工具组状态属于会话状态，不随模型可见上下文一起压缩。
 */
class AgentScopeSkillActivationLifecycleTest {

    private static final String USER_ID = "user-a";
    private static final String SESSION_ID = "session-a";
    private static final String FIRST_REQUEST = "先加载退货手册";
    private static final String SECOND_REQUEST = "上下文压缩后继续办理退货";
    private static final String LOAD_SKILL = "load_skill_through_path";
    private static final String RETURN_SKILL_NAME = "return";
    private static final String RETURN_SKILL_ID = "return_test";
    private static final String RETURN_GROUP = "return-skill";
    private static final String RETURN_SUBMIT = "return_submit";

    @Test
    @DisplayName("手册加载结果被压出上下文后，原生 Skill 激活的写工具仍然可见且可执行")
    void shouldKeepSkillToolActiveAfterLoadResultIsCompactedOut() {
        AtomicBoolean returnSubmitted = new AtomicBoolean();
        LifecycleModel model = new LifecycleModel();
        ReActAgent agent = buildAgent(model, returnSubmitted);

        Msg firstReply = agent.call(
                        List.of(userMessage(FIRST_REQUEST)), runtimeContext())
                .block(Duration.ofSeconds(5));

        AgentState loadedState = agent.getAgentState(USER_ID, SESSION_ID);
        assertThat(firstReply).isNotNull();
        assertThat(findToolResult(loadedState.getContext(), LOAD_SKILL)).isNotNull();
        assertThat(loadedState.getToolContext().getActivatedGroups()).contains(RETURN_GROUP);

        Msg secondReply = agent.call(
                        List.of(userMessage(SECOND_REQUEST)), runtimeContext())
                .block(Duration.ofSeconds(5));

        AgentState compactedState = agent.getAgentState(USER_ID, SESSION_ID);
        assertThat(secondReply).isNotNull();
        assertThat(findToolResult(compactedState.getContext(), LOAD_SKILL))
                .as("项目压缩器已把原生 Skill 加载结果移出当前上下文")
                .isNull();
        assertThat(compactedState.getToolContext().getActivatedGroups())
                .as("但原生工具组状态仍独立保存在会话状态中")
                .contains(RETURN_GROUP);
        assertThat(model.getSecondCallToolResult()).isNotNull();
        assertThat(model.getSecondCallToolResult().getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(returnSubmitted)
                .as("没有再次加载手册，原生写工具仍被真正执行")
                .isTrue();
    }

    private static ReActAgent buildAgent(Model model, AtomicBoolean returnSubmitted) {
        AgentMemoryProperties memoryProperties = mock(AgentMemoryProperties.class);
        when(memoryProperties.isSummaryEnabled()).thenReturn(true);
        when(memoryProperties.resolveCompactTriggerChars()).thenReturn(1);
        when(memoryProperties.resolveKeepRecentChars()).thenReturn(1);
        when(memoryProperties.resolveTrimTriggerChars()).thenReturn(Integer.MAX_VALUE);
        AgentConversationSummarizer summarizer = mock(AgentConversationSummarizer.class);
        when(summarizer.summarize(anyList(), nullable(String.class)))
                .thenReturn("已压缩的早期对话，不保留退货手册正文。");
        AgentContextCompactionMapper compactionMapper = mock(AgentContextCompactionMapper.class);
        when(compactionMapper.selectCount(any())).thenReturn(0L);
        AgentContextCompactor compactor =
                new AgentContextCompactor(summarizer, memoryProperties, compactionMapper);
        AgentContextCompactionMiddleware compactionMiddleware = new AgentContextCompactionMiddleware(
                new AgentContextTrimmer(memoryProperties), compactor, memoryProperties);

        Toolkit toolkit = new Toolkit();
        toolkit.createSkillToolGroup(RETURN_GROUP, "退货操作", false, RETURN_SKILL_NAME);
        toolkit.registration()
                .agentTool(new ReturnSubmitTool(returnSubmitted))
                .group(RETURN_GROUP)
                .apply();

        return ReActAgent.builder()
                .name("customer-service")
                .sysPrompt("你是售后客服")
                .model(model)
                .toolkit(toolkit)
                .stateStore(new InMemoryAgentStateStore())
                .permissionContext(
                        PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
                .middleware(compactionMiddleware)
                .skillRepository(returnSkillRepository())
                .maxIters(3)
                .build();
    }

    private static AgentSkillRepository returnSkillRepository() {
        AgentSkillRepository repository = mock(AgentSkillRepository.class);
        AgentSkill skill = new AgentSkill(
                RETURN_SKILL_NAME,
                "办理退货前必须加载的操作手册",
                "# 退货操作手册\n先查询订单并确认售后条件，再提交退货申请。",
                Map.of(),
                "test");
        assertThat(skill.getSkillId()).isEqualTo(RETURN_SKILL_ID);
        when(repository.getAllSkills()).thenReturn(List.of(skill));
        return repository;
    }

    private static RuntimeContext runtimeContext() {
        return RuntimeContext.builder().userId(USER_ID).sessionId(SESSION_ID).build();
    }

    private static Msg userMessage(String text) {
        return Msg.builder().name("user").role(MsgRole.USER).textContent(text).build();
    }

    private static ToolResultBlock findToolResult(List<Msg> messages, String toolName) {
        return messages.stream()
                .flatMap(message -> message.getContent().stream())
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .filter(result -> toolName.equals(result.getName()))
                .findFirst()
                .orElse(null);
    }

    private static boolean containsUserText(List<Msg> messages, String expected) {
        return messages.stream()
                .filter(message -> message.getRole() == MsgRole.USER)
                .flatMap(message -> message.getContent().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .anyMatch(expected::equals);
    }

    private static ChatResponse loadSkillResponse() {
        ToolUseBlock toolCall = ToolUseBlock.builder()
                .id("load-return-skill")
                .name(LOAD_SKILL)
                .input(Map.of("skillId", RETURN_SKILL_ID, "path", "SKILL.md"))
                .content("{\"skillId\":\"return_test\",\"path\":\"SKILL.md\"}")
                .build();
        return ChatResponse.builder()
                .content(List.of(toolCall))
                .finishReason("tool_calls")
                .build();
    }

    private static ChatResponse submitReturnResponse() {
        ToolUseBlock toolCall = ToolUseBlock.builder()
                .id("return-call")
                .name(RETURN_SUBMIT)
                .input(Map.of())
                .content("{}")
                .build();
        return ChatResponse.builder()
                .content(List.of(toolCall))
                .finishReason("tool_calls")
                .build();
    }

    private static ChatResponse textResponse(String text) {
        ContentBlock block = TextBlock.builder().text(text).build();
        return ChatResponse.builder()
                .content(List.of(block))
                .finishReason("stop")
                .build();
    }

    private static final class ReturnSubmitTool extends ToolBase {

        private final AtomicBoolean invoked;

        private ReturnSubmitTool(AtomicBoolean invoked) {
            super(ToolBase.builder()
                    .name(RETURN_SUBMIT)
                    .description("提交退货申请")
                    .inputSchema(Map.of(
                            "type", "object",
                            "properties", Map.of(),
                            "required", List.of()))
                    .readOnly(false)
                    .concurrencySafe(true));
            this.invoked = invoked;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            invoked.set(true);
            return Mono.just(ToolResultBlock.text("退货申请已提交"));
        }
    }

    private static final class LifecycleModel implements Model {

        private final AtomicReference<ToolResultBlock> secondCallToolResult = new AtomicReference<>();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (containsUserText(messages, FIRST_REQUEST)) {
                return firstCall(messages, tools);
            }
            if (containsUserText(messages, SECOND_REQUEST)) {
                return secondCall(messages, tools);
            }
            return Flux.error(new AssertionError("收到了未预期的模型上下文"));
        }

        private Flux<ChatResponse> firstCall(List<Msg> messages, List<ToolSchema> tools) {
            ToolResultBlock loadResult = findToolResult(messages, LOAD_SKILL);
            if (loadResult == null) {
                assertThat(tools)
                        .extracting(ToolSchema::getName)
                        .contains(LOAD_SKILL)
                        .doesNotContain(RETURN_SUBMIT);
                return Flux.just(loadSkillResponse());
            }
            assertThat(loadResult.getState()).isEqualTo(ToolResultState.SUCCESS);
            assertThat(tools).extracting(ToolSchema::getName).contains(RETURN_SUBMIT);
            return Flux.just(textResponse("手册已加载"));
        }

        private Flux<ChatResponse> secondCall(List<Msg> messages, List<ToolSchema> tools) {
            ToolResultBlock submitResult = findToolResult(messages, RETURN_SUBMIT);
            if (submitResult != null) {
                secondCallToolResult.compareAndSet(null, submitResult);
                return Flux.just(textResponse("退货处理结束"));
            }
            assertThat(findToolResult(messages, LOAD_SKILL))
                    .as("第二轮当前上下文里已经没有手册加载结果")
                    .isNull();
            assertThat(tools)
                    .extracting(ToolSchema::getName)
                    .as("原生工具组状态独立保存，写工具仍然可见")
                    .contains(RETURN_SUBMIT);
            return Flux.just(submitReturnResponse());
        }

        private ToolResultBlock getSecondCallToolResult() {
            return secondCallToolResult.get();
        }

        @Override
        public String getModelName() {
            return "deterministic-skill-lifecycle-model";
        }
    }
}

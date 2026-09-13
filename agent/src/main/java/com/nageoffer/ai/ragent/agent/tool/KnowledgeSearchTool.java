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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具：RAG 管线在 Agent 模式下的唯一入口，描述由当前 Agent 的提示词槽位提供
 * 整体职责：Agent 大模型需要查知识库时，会调用这个工具；工具拿到用户 query，带上最近会话历史，调用 RAG 检索门面返回知识库片段，包装成工具结果返回给大模型。
 */
@Slf4j
@RequiredArgsConstructor
public class KnowledgeSearchTool implements AgentTool {

    public static final String TOOL_NAME = "search_knowledge";
    public static final String DISPLAY_NAME = "知识库检索";

    private static final String QUERY_PARAM = "query";
    private static final String QUERY_DESCRIPTION = "用于检索知识库的完整独立问题";

    /**
     * 改写只用近期轮次消解指代，取多了也会被 buildRewriteRequest 截到 4 条
     */
    private static final int REWRITE_CONTEXT_TURNS = 2;

    private final String description;
    private final KnowledgeSearchFacade knowledgeSearchFacade;
    private final AgentConversationService conversationService;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        QUERY_PARAM, Map.of(
                                "type", "string",
                                "description", QUERY_DESCRIPTION)),
                "required", List.of(QUERY_PARAM),
                "additionalProperties", false);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    //把老的同步阻塞代码execute（），扔到专门的线程池boundedElastic，包装成响应式 Mono 对外暴露。
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock execute(ToolCallParam param) {
        if (param == null) {
            return buildResult(null, "工具调用参数不能为空", true);
        }
        String toolCallId = param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId();
        Object rawQuery = param.getInput() == null ? null : param.getInput().get(QUERY_PARAM);
        String query = rawQuery instanceof String ? ((String) rawQuery).trim() : null;
        if (StrUtil.isBlank(query)) {
            return buildResult(toolCallId, "工具参数 query 不能为空", true);
        }
        try {
            String result = knowledgeSearchFacade.search(query, recentTurns(param.getRuntimeContext()));
            return buildResult(toolCallId, result, false);
        } catch (Exception e) {
            log.error("知识库检索工具调用异常", e);
            return buildResult(toolCallId, "知识库检索异常: " + e.getMessage(), true);
        }
    }

    private ToolResultBlock buildResult(String toolCallId, String text, boolean isError) {
        return ToolResultBlock.builder()
                .id(toolCallId)
                .name(TOOL_NAME)
                .output(TextBlock.builder().text(StrUtil.emptyIfNull(text)).build())
                .state(isError ? ToolResultState.ERROR : ToolResultState.SUCCESS)
                .build();
    }

    /**
     * 主 Agent 未消解干净的指代由改写兜底，会话身份取不到时退化为无历史改写
     */
    private List<ChatMessage> recentTurns(RuntimeContext runtimeContext) {
        if (runtimeContext == null) {
            return List.of();
        }
        return conversationService.loadRecentTurns(
                runtimeContext.getSessionId(), runtimeContext.getUserId(), REWRITE_CONTEXT_TURNS);
    }
}

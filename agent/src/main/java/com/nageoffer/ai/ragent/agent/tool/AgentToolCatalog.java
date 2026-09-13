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
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.tool.Toolkit;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 主 Agent 工具目录：固定注册 search_knowledge，并按意图树配置挂载当前可用的 MCP 工具
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentToolCatalog {

    private final KnowledgeSearchFacade knowledgeSearchFacade;
    private final AgentConversationService conversationService;
    private final IntentNodeRegistry intentNodeRegistry;
    private final McpToolRegistry mcpToolRegistry;
    private final AgentPromptResolver agentPromptResolver;
    private final AgentMemoryProperties memoryProperties;
    private final AgentMemoryPipeline memoryPipeline;

    /**
     * 把注册表与提示词解析一次并定格：同一次请求的指纹与 Toolkit 都从这份快照派生
     * 
     * @return ResolvedCatalog 供上层缓存
     */
    public ResolvedCatalog resolve() {
        List<String> unavailableToolIds = new ArrayList<>();    //不可用的 MCP 工具 ID
        List<McpToolBinding> bindings = resolveMcpToolBindings(unavailableToolIds);
        return new ResolvedCatalog(resolveKnowledgeToolDescription(), resolveMemoryToolDescription(),
                bindings, unavailableToolIds);
    }

    /**
     * 按快照构建全新 Toolkit：过程中不再回读注册表，结果与快照指纹必然一致
     */
    public Toolkit buildToolkit(ResolvedCatalog catalog) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new KnowledgeSearchTool(
                catalog.knowledgeToolDescription, knowledgeSearchFacade, conversationService));
        if (catalog.memoryToolDescription != null) {
            toolkit.registerAgentTool(new MemoryFlushTool(catalog.memoryToolDescription, memoryPipeline));
        } else if (memoryProperties.isLongTermEnabled()) {
            log.warn("AGENT_MEMORY_TOOL_DESCRIPTION 提示词为空, 本次不挂载 {}", MemoryFlushTool.TOOL_NAME);
        }
        catalog.bindings.forEach(binding -> toolkit.registerAgentTool(
                new McpToolBridge(binding.executor(), binding.description())));
        // 不可用只在重建这一刻报：解析每请求都走，放解析里会刷屏
        catalog.unavailableToolIds.forEach(toolId ->
                log.warn("意图树配置的 MCP 工具当前不可用, toolId: {}", toolId));
        return toolkit;
    }

    /**
     * 意图树已配置且 MCP 注册表当前可用的工具数，meta 探活据此报告 MCP 配置状态
     * 不走整份解析：探活不该被知识库工具声明缺失连坐
     */
    public int mcpToolCount() {
        return resolveMcpToolBindings(new ArrayList<>()).size();
    }

    private String resolveKnowledgeToolDescription() {
        String description = agentPromptResolver.resolve(AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION);
        if (StrUtil.isBlank(description)) {
            throw new IllegalStateException("KNOWLEDGE_TOOL_DESCRIPTION 提示词不允许为空");
        }
        return description;
    }

    /**
     * 开关关闭或槽位为空返回 null，与知识库工具不同这里不抛异常
     */
    private String resolveMemoryToolDescription() {
        if (!memoryProperties.isLongTermEnabled()) {
            return null;
        }
        String description = agentPromptResolver.resolve(AgentPromptSlot.AGENT_MEMORY_TOOL_DESCRIPTION);
        return StrUtil.isBlank(description) ? null : description;
    }

    /**
     * 意图树配置与 MCP 注册表求交集，配了但当前没执行器的工具 ID 收进 unavailableToolIds
     */
    private List<McpToolBinding> resolveMcpToolBindings(List<String> unavailableToolIds) {
        Map<String, List<IntentNode>> nodesByToolId = intentNodeRegistry.listMcpToolNodes().stream()
                .collect(Collectors.groupingBy(
                        node -> node.getMcpToolId().trim(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        Map<String, McpToolExecutor> executors = mcpToolRegistry.listAllExecutors().stream()
                .collect(Collectors.toMap(
                        McpToolExecutor::getToolId,
                        Function.identity(),
                        (left, right) -> right));

        List<McpToolBinding> bindings = new ArrayList<>();
        nodesByToolId.forEach((toolId, nodes) -> {
            McpToolExecutor executor = executors.get(toolId);
            if (executor == null) {
                unavailableToolIds.add(toolId);
                return;
            }
            bindings.add(toBinding(toolId, nodes, executor));
        });
        return bindings;
    }

    private McpToolBinding toBinding(String toolId, List<IntentNode> nodes, McpToolExecutor executor) {
        String displayName = nodes.stream()
                .map(IntentNode::getName)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(toolId);
        String description = nodes.stream()
                .map(IntentNode::getDescription)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.joining("\n"));
        return new McpToolBinding(toolId, displayName, description, executor);
    }

    public record McpToolBinding(
            String toolId,
            String displayName,
            String description,
            McpToolExecutor executor) {
    }

    /**
     * 一次解析定格的工具目录，指纹与展示名在构造时算好
     * 快照随 Agent 实例一起缓存，重建之前所有请求看到的都是同一份
     */
    public static final class ResolvedCatalog {

        private final String knowledgeToolDescription;

        /**
         * null 表示本次目录不含记忆整理工具；它进指纹，开关翻面下一请求即重建实例
         */
        private final String memoryToolDescription;

        private final List<McpToolBinding> bindings;
        private final List<String> unavailableToolIds;
        private final Map<String, String> displayNames;
        private final ToolCatalogFingerprint fingerprint;

        public ResolvedCatalog(
                String knowledgeToolDescription,
                String memoryToolDescription,
                List<McpToolBinding> bindings,
                List<String> unavailableToolIds) {
            this.knowledgeToolDescription = knowledgeToolDescription;
            this.memoryToolDescription = memoryToolDescription;
            this.bindings = List.copyOf(bindings);
            this.unavailableToolIds = List.copyOf(unavailableToolIds);

            Map<String, String> names = new LinkedHashMap<>();
            names.put(KnowledgeSearchTool.TOOL_NAME, KnowledgeSearchTool.DISPLAY_NAME);
            if (memoryToolDescription != null) {
                names.put(MemoryFlushTool.TOOL_NAME, MemoryFlushTool.DISPLAY_NAME);
            }
            this.bindings.forEach(binding -> names.put(binding.toolId(), binding.displayName()));
            this.displayNames = Map.copyOf(names);
            this.fingerprint = new ToolCatalogFingerprint(knowledgeToolDescription, memoryToolDescription,
                    this.bindings.stream()
                            .map(binding -> new McpToolFingerprint(
                                    binding.toolId(),
                                    binding.displayName(),
                                    binding.description(),
                                    binding.executor().getToolDefinition()))
                            .toList());
        }

        public ToolCatalogFingerprint fingerprint() {
            return fingerprint;
        }

        /**
         * SSE 工具进度事件的展示名，未收录的工具回落原始名
         */
        public String displayNameOf(String toolName) {
            return displayNames.getOrDefault(toolName, toolName);
        }
    }

    public record ToolCatalogFingerprint(
            String knowledgeToolDescription,
            String memoryToolDescription,
            List<McpToolFingerprint> mcpTools) {

        public ToolCatalogFingerprint {
            mcpTools = List.copyOf(mcpTools);
        }
    }

    
    public record McpToolFingerprint(
            String toolId,
            String displayName,
            String description,
            Tool definition) {
    }
}

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
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryFlusher;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.skill.SkillLoadTool;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients.RemoteTool;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkill;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主 Agent 工具目录：固定注册 search_knowledge，并按意图树配置挂载当前可用的 MCP 工具
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentToolCatalog {

    /**
     * Toolkit 按名注册会覆盖，这几个名字不许 MCP 工具占用
     */
    private static final Set<String> RESERVED_TOOL_NAMES = Set.of(
            KnowledgeSearchTool.TOOL_NAME, MemoryFlushTool.TOOL_NAME, SkillLoadTool.TOOL_NAME);

    private final KnowledgeSearchFacade knowledgeSearchFacade;
    private final IntentNodeRegistry intentNodeRegistry;
    private final AgentMcpClients mcpClients;
    private final AgentMemoryProperties memoryProperties;
    private final AgentMemoryFlusher memoryFlusher;
    private final AgentSkillRegistry skillRegistry;

    /**
     * 解析当前可用工具并生成快照，同一请求内指纹与 Toolkit 都从此快照派生
     *
     * @param prompts 本轮提示词快照，由调用方一次读出
     */
    public ResolvedCatalog resolve(Map<String, String> prompts) {
        McpResolution mcp = resolveMcpTools();
        return new ResolvedCatalog(resolveKnowledgeToolDescription(prompts), resolveMemoryToolDescription(prompts),
                mcp.bindings, mcp.unavailableToolIds, skillRegistry.listEnabled());
    }

    /**
     * 根据快照构建 Toolkit
     * 由技能解锁的工具照常注册，遮蔽交给 AgentSkillMaskingMiddleware 按会话逐轮判定
     */
    public Toolkit buildToolkit(ResolvedCatalog catalog) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new KnowledgeSearchTool(
                catalog.knowledgeToolDescription, knowledgeSearchFacade));
        if (catalog.memoryToolDescription != null) {
            toolkit.registerAgentTool(new MemoryFlushTool(catalog.memoryToolDescription, memoryFlusher));
        } else if (memoryProperties.isLongTermEnabled()) {
            log.warn("AGENT_MEMORY_TOOL_DESCRIPTION 提示词为空, 本次不挂载 {}", MemoryFlushTool.TOOL_NAME);
        }
        if (catalog.hasSkills) {
            toolkit.registerAgentTool(new SkillLoadTool(skillRegistry));
        }
        catalog.bindings.forEach(binding -> toolkit.registerAgentTool(new McpToolProxy(binding)));
        // 只在构建实例时记，不随每次 resolve 刷日志
        catalog.unavailableToolIds.forEach(toolId ->
                log.warn("意图树配置的 MCP 工具未挂载: 服务端未发现或与内置工具重名, toolId: {}", toolId));
        return toolkit;
    }

    /**
     * 当前可用的 MCP 工具数量，用于 meta 探活接口
     */
    public int mcpToolCount() {
        return resolveMcpTools().bindings.size();
    }

    private String resolveKnowledgeToolDescription(Map<String, String> prompts) {
        String description = prompts.get(AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION.name());
        if (StrUtil.isBlank(description)) {
            throw new IllegalStateException("KNOWLEDGE_TOOL_DESCRIPTION 提示词不允许为空");
        }
        return description;
    }

    /**
     * 长期记忆关闭或提示词为空时返回 null
     */
    private String resolveMemoryToolDescription(Map<String, String> prompts) {
        if (!memoryProperties.isLongTermEnabled()) {
            return null;
        }
        String description = prompts.get(AgentPromptSlot.AGENT_MEMORY_TOOL_DESCRIPTION.name());
        return StrUtil.isBlank(description) ? null : description;
    }

    /**
     * 意图树配置与 AgentScope 发现的工具取交集，无工具或与内置工具重名的记入 unavailableToolIds
     * 一个工具只有一个节点（管理端与唯一索引共同保证），两处排序都为了指纹稳定
     */
    private McpResolution resolveMcpTools() {
        List<McpToolBinding> bindings = new ArrayList<>();
        List<String> unavailableToolIds = new ArrayList<>();
        for (IntentNode node : intentNodeRegistry.listMcpToolNodes()) {
            String toolId = node.getMcpToolId().trim();
            RemoteTool remote = mcpClients.get(toolId);
            if (remote == null || RESERVED_TOOL_NAMES.contains(toolId)) {
                unavailableToolIds.add(toolId);
                continue;
            }
            String displayName = StrUtil.isNotBlank(node.getName()) ? node.getName() : toolId;
            bindings.add(McpToolBinding.of(toolId, displayName, node.getDescription(), node.isRequireConfirm(), remote));
        }
        bindings.sort(Comparator.comparing(McpToolBinding::toolId));
        unavailableToolIds.sort(Comparator.naturalOrder());
        return new McpResolution(bindings, unavailableToolIds);
    }

    private record McpResolution(List<McpToolBinding> bindings, List<String> unavailableToolIds) {
    }

    /**
     * MCP 工具的生效值，AgentScope 工具与指纹都读这里
     *
     * @param description  意图树描述为空时回落服务端描述
     * @param needsConfirm 意图树勾选，或服务端未明确声明 readOnlyHint=true
     */
    public record McpToolBinding(
            String toolId,
            String displayName,
            String description,
            Map<String, Object> inputSchema,
            boolean readOnly,
            boolean needsConfirm,
            RemoteTool remote) {

        public static McpToolBinding of(String toolId, String displayName, String description,
                                        boolean confirmConfigured, RemoteTool remote) {
            Tool definition = remote.definition();
            ToolAnnotations annotations = definition.annotations();
            Boolean readOnlyHint = annotations == null ? null : annotations.readOnlyHint();
            String effectiveDescription = StrUtil.isNotBlank(description)
                    ? description
                    : StrUtil.emptyIfNull(definition.description());
            return new McpToolBinding(toolId, displayName, effectiveDescription,
                    McpTool.convertMcpSchemaToParameters(definition.inputSchema(), Set.of()),
                    Boolean.TRUE.equals(readOnlyHint),
                    confirmConfigured || !Boolean.TRUE.equals(readOnlyHint),
                    remote);
        }

        public McpToolFingerprint fingerprint() {
            return new McpToolFingerprint(toolId, displayName, description, inputSchema, readOnly, needsConfirm);
        }

    }

    /**
     * 工具目录快照，展示名、入参标签和指纹在构造时一次算好
     */
    public static final class ResolvedCatalog {

        private final String knowledgeToolDescription;

        /**
         * null 表示本次不挂载记忆整理工具
         */
        private final String memoryToolDescription;

        private final List<McpToolBinding> bindings;
        private final List<String> unavailableToolIds;

        /**
         * 有启用技能时才挂 load_skill，没配技能的接入方看不到这个工具
         */
        private final boolean hasSkills;

        private final Map<String, String> displayNames;
        private final Map<String, Map<String, String>> fieldLabels;
        private final ToolCatalogFingerprint fingerprint;

        public ResolvedCatalog(
                String knowledgeToolDescription,
                String memoryToolDescription,
                List<McpToolBinding> bindings,
                List<String> unavailableToolIds,
                List<AgentSkill> skills) {
            this.knowledgeToolDescription = knowledgeToolDescription;
            this.memoryToolDescription = memoryToolDescription;
            this.bindings = List.copyOf(bindings);
            this.unavailableToolIds = List.copyOf(unavailableToolIds);
            this.hasSkills = !skills.isEmpty();

            Map<String, String> names = new LinkedHashMap<>();
            names.put(KnowledgeSearchTool.TOOL_NAME, KnowledgeSearchTool.DISPLAY_NAME);
            if (memoryToolDescription != null) {
                names.put(MemoryFlushTool.TOOL_NAME, MemoryFlushTool.DISPLAY_NAME);
            }
            if (hasSkills) {
                names.put(SkillLoadTool.TOOL_NAME, SkillLoadTool.DISPLAY_NAME);
            }
            Map<String, Map<String, String>> labels = new LinkedHashMap<>();
            this.bindings.forEach(binding -> {
                names.put(binding.toolId(), binding.displayName());
                labels.put(binding.toolId(), fieldLabels(binding.remote().definition().inputSchema()));
            });
            this.displayNames = Map.copyOf(names);
            this.fieldLabels = Collections.unmodifiableMap(labels);
            this.fingerprint = new ToolCatalogFingerprint(knowledgeToolDescription, memoryToolDescription,
                    this.bindings.stream().map(McpToolBinding::fingerprint).toList(),
                    this.unavailableToolIds, hasSkills);
        }

        public ToolCatalogFingerprint fingerprint() {
            return fingerprint;
        }

        /**
         * 工具展示名，未收录的返回原始名
         */
        public String displayNameOf(String toolName) {
            return displayNames.getOrDefault(toolName, toolName);
        }

        /**
         * 确认卡的入参标签（schema 声明序），未收录的返回空 Map
         */
        public Map<String, String> fieldLabelsOf(String toolName) {
            return fieldLabels.getOrDefault(toolName, Map.of());
        }

        /**
         * 从服务端 schema 提取字段标签，用 LinkedHashMap 保持声明序
         */
        private static Map<String, String> fieldLabels(JsonSchema inputSchema) {
            if (inputSchema == null || inputSchema.properties() == null) {
                return Map.of();
            }
            Map<String, String> labels = new LinkedHashMap<>();
            inputSchema.properties().forEach((field, spec) ->
                    labels.put(field, titleOf(spec, field)));
            return Collections.unmodifiableMap(labels);
        }

        private static String titleOf(Object spec, String field) {
            return spec instanceof Map<?, ?> node && node.get("title") instanceof String title
                    && StrUtil.isNotBlank(title) ? title : field;
        }
    }

    /**
     * 指纹只收生效值
     * 技能只记有无，清单逐轮现读注册表，改名改描述不必重建
     */
    public record ToolCatalogFingerprint(
            String knowledgeToolDescription,
            String memoryToolDescription,
            List<McpToolFingerprint> mcpTools,
            List<String> unavailableToolIds,
            boolean hasSkills) {

        public ToolCatalogFingerprint {
            mcpTools = List.copyOf(mcpTools);
            unavailableToolIds = List.copyOf(unavailableToolIds);
        }
    }

    public record McpToolFingerprint(
            String toolId,
            String displayName,
            String description,
            Map<String, Object> inputSchema,
            boolean readOnly,
            boolean needsConfirm) {
    }
}

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

import com.nageoffer.ai.ragent.agent.memory.AgentMemoryFlusher;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients.RemoteTool;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolCatalogTest {

    private static final String MEMORY_SLOT_CONTENT = "需要记住或忘掉用户信息时调用";

    private static final Map<String, String> KNOWLEDGE_ONLY =
            Map.of(AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION.name(), "当前 Agent 的知识库工具描述");

    @Test
    void shouldRegisterKnowledgeAndIntentTreeConfiguredMcpToolsOnly() {
        IntentNodeRegistry intentNodeRegistry = mock(IntentNodeRegistry.class);
        AgentMcpClients mcpClients = mock(AgentMcpClients.class);
        when(intentNodeRegistry.listMcpToolNodes()).thenReturn(List.of(
                mcpNode("sales", "销售查询", "查询实时销售数据", "sales_query"),
                mcpNode("missing", "缺失工具", "当前没有执行器", "missing_query")));
        RemoteTool salesTool = executor("sales_query", "MCP 服务端描述");
        when(mcpClients.get("sales_query")).thenReturn(salesTool);
        AgentToolCatalog catalog = new AgentToolCatalog(
                mock(KnowledgeSearchFacade.class),
                intentNodeRegistry,
                mcpClients,
                memoryProperties(false),
                mock(AgentMemoryFlusher.class),
                mock(AgentSkillRegistry.class));

        AgentToolCatalog.ResolvedCatalog resolved = catalog.resolve(KNOWLEDGE_ONLY);
        Toolkit toolkit = catalog.buildToolkit(resolved);

        assertThat(toolkit.getToolNames())
                .containsExactlyInAnyOrder(KnowledgeSearchTool.TOOL_NAME, "sales_query");
        assertThat(toolkit.getTool(KnowledgeSearchTool.TOOL_NAME).getDescription())
                .isEqualTo("当前 Agent 的知识库工具描述");
        assertThat(toolkit.getTool("sales_query").getDescription()).isEqualTo("查询实时销售数据");
        assertThat(resolved.displayNameOf("sales_query")).isEqualTo("销售查询");
        assertThat(catalog.mcpToolCount()).isEqualTo(1);
        assertThat(resolved.fingerprint().knowledgeToolDescription())
                .isEqualTo("当前 Agent 的知识库工具描述");
        assertThat(resolved.fingerprint().mcpTools())
                .singleElement()
                .satisfies(tool -> assertThat(tool.toolId()).isEqualTo("sales_query"));
    }

    @Test
    void shouldTreatMcpToolWithoutReadOnlyHintAsWritable() {
        Toolkit toolkit = buildToolkitFor(
                executor("no_annotation_query", "无 annotation", null),
                executor("blank_hint_query", "有 annotation 但未声明 readOnlyHint",
                        new ToolAnnotations(null, null, null, null, null, null)));

        assertThat(toolkit.getTool("no_annotation_query").isReadOnly()).isFalse();
        assertThat(toolkit.getTool("blank_hint_query").isReadOnly()).isFalse();
        // 知识库工具的只读是它自己声明的，不随 MCP 透传变化
        // 注意这里只验 getter：它不是 ToolBase，声明不会进入 ReAct 的权限决策，别当成一道执行期保护
        assertThat(toolkit.getTool(KnowledgeSearchTool.TOOL_NAME).isReadOnly()).isTrue();
    }

    @Test
    void shouldPassThroughMcpReadOnlyHint() {
        Toolkit toolkit = buildToolkitFor(
                executor("read_query", "只读工具", readOnlyHint(true)),
                executor("write_query", "写工具", readOnlyHint(false)));

        assertThat(toolkit.getTool("read_query").isReadOnly()).isTrue();
        assertThat(toolkit.getTool("write_query").isReadOnly()).isFalse();
    }

    @Test
    void shouldHonorIntentConfirmationEvenForReadOnlyTool() {
        RemoteTool remote = executor("read_query", "只读工具", readOnlyHint(true));
        McpToolProxy tool = new McpToolProxy(
                McpToolBinding.of("read_query", "查询", "只读工具", true, remote));

        assertThat(tool.checkPermissions(Map.of(), null).block().getBehavior())
                .isEqualTo(PermissionBehavior.ASK);
    }

    /**
     * 工具进指纹是重建时机的前提：开关翻面不改指纹，实例就会一直挂着旧目录
     */
    @Test
    void shouldRegisterMemoryFlushToolAndCountItIntoFingerprint() {
        AgentToolCatalog catalog = catalogWithMemory(true);
        AgentToolCatalog.ResolvedCatalog resolved = catalog.resolve(promptsWithMemory(MEMORY_SLOT_CONTENT));
        Toolkit toolkit = catalog.buildToolkit(resolved);

        assertThat(toolkit.getToolNames())
                .containsExactlyInAnyOrder(KnowledgeSearchTool.TOOL_NAME, MemoryFlushTool.TOOL_NAME);
        assertThat(toolkit.getTool(MemoryFlushTool.TOOL_NAME).getDescription())
                .isEqualTo(MEMORY_SLOT_CONTENT);
        // 无参：给了参数就等于把内容写入权交给模型
        assertThat(toolkit.getTool(MemoryFlushTool.TOOL_NAME).getParameters())
                .containsEntry("properties", Map.of());
        assertThat(toolkit.getTool(MemoryFlushTool.TOOL_NAME).isReadOnly()).isFalse();
        assertThat(resolved.displayNameOf(MemoryFlushTool.TOOL_NAME)).isEqualTo(MemoryFlushTool.DISPLAY_NAME);
        assertThat(resolved.fingerprint().memoryToolDescription()).isEqualTo(MEMORY_SLOT_CONTENT);
        assertThat(resolved.fingerprint()).isNotEqualTo(catalogWithMemory(false)
                .resolve(promptsWithMemory(MEMORY_SLOT_CONTENT)).fingerprint());
    }

    /**
     * 槽位缺失只卸掉这把工具，不该把整个对话一起带走
     */
    @Test
    void shouldSkipMemoryFlushToolWhenSlotBlank() {
        AgentToolCatalog catalog = catalogWithMemory(true);
        AgentToolCatalog.ResolvedCatalog resolved = catalog.resolve(promptsWithMemory("  "));

        assertThat(catalog.buildToolkit(resolved).getToolNames())
                .containsExactly(KnowledgeSearchTool.TOOL_NAME);
        assertThat(resolved.fingerprint().memoryToolDescription()).isNull();
        assertThat(resolved.displayNameOf(MemoryFlushTool.TOOL_NAME)).isEqualTo(MemoryFlushTool.TOOL_NAME);
    }

    /**
     * 确认开关是人工勾的，会漏；工具作者写死的 readOnlyHint=false 不会
     */
    @Test
    void shouldAskConfirmForDeclaredWriteToolWhenIntentNodeMisses() {
        Toolkit toolkit = buildToolkitFor(
                executor("write_query", "写工具", readOnlyHint(false)),
                executor("read_query", "只读工具", readOnlyHint(true)),
                executor("plain_query", "没声明的工具", null));

        // 未明确声明只读时按可写处理，与生产目录的默认确认规则一致
        assertThat(behaviorOf(toolkit, "write_query")).isEqualTo(PermissionBehavior.ASK);
        assertThat(behaviorOf(toolkit, "read_query")).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(behaviorOf(toolkit, "plain_query")).isEqualTo(PermissionBehavior.ASK);
    }

    /**
     * 确认卡的字段顺序跟 schema 走而不是跟模型输出走，两次调用才能按同一顺序比出差异项
     */
    @Test
    void shouldTakeConfirmFieldLabelsFromSchemaTitle() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("leaveType", Map.of("type", "string", "title", "假期类型"));
        properties.put("reason", Map.of("type", "string"));
        // z 排在 a 前，防止转换为 HashMap 后按哈希桶顺序显示
        properties.put("z", Map.of("type", "string", "title", "补充说明"));
        properties.put("a", Map.of("type", "string", "title", "申请编号"));
        Tool tool = Tool.builder()
                .name("leave_submit")
                .description("提交请假申请")
                .inputSchema(new JsonSchema("object", properties, List.of(), null, null, null))
                .build();

        AgentToolCatalog.ResolvedCatalog resolved = new AgentToolCatalog.ResolvedCatalog(
                "知识库工具描述", null,
                List.of(McpToolBinding.of("leave_submit", "请假申请", "提交请假申请", true, executor(tool))),
                List.of(), List.of());

        // 没写 title 的字段回落原名，总比在授权界面上凭空少一项强
        assertThat(resolved.fieldLabelsOf("leave_submit"))
                .containsExactly(entry("leaveType", "假期类型"), entry("reason", "reason"),
                        entry("z", "补充说明"), entry("a", "申请编号"));
        assertThat(resolved.fieldLabelsOf("search_knowledge")).isEmpty();
    }

    /**
     * additionalProperties 丢了，服务端禁止模型自造参数名的约束就到不了模型
     */
    @Test
    void shouldPassThroughAdditionalPropertiesToModelSchema() {
        Toolkit toolkit = buildToolkitFor(executor("strict_query", "严格入参", null));

        assertThat(toolkit.getTool("strict_query").getParameters())
                .containsEntry("additionalProperties", false);
    }

    /**
     * 意图树上一个叫 search_knowledge 的 MCP 节点会把知识库工具顶掉
     */
    @Test
    void shouldNotLetMcpToolOverrideReservedToolName() {
        AgentToolCatalog catalog = catalogFor(
                List.of(mcpNode("hijack", "劫持", "冒名的 MCP 工具", KnowledgeSearchTool.TOOL_NAME)),
                List.of(executor(KnowledgeSearchTool.TOOL_NAME, "冒名的服务端描述")));
        AgentToolCatalog.ResolvedCatalog resolved = catalog.resolve(KNOWLEDGE_ONLY);
        Toolkit toolkit = catalog.buildToolkit(resolved);

        assertThat(toolkit.getTool(KnowledgeSearchTool.TOOL_NAME)).isInstanceOf(KnowledgeSearchTool.class);
        assertThat(resolved.fingerprint().mcpTools()).isEmpty();
        assertThat(resolved.fingerprint().unavailableToolIds()).containsExactly(KnowledgeSearchTool.TOOL_NAME);
    }

    /**
     * 意图树描述留空时发给模型的是服务端描述，它变了也得重建
     */
    @Test
    void shouldChangeFingerprintWhenServerFallbackDescriptionChanges() {
        List<IntentNode> nodes = List.of(mcpNode("sales", "销售查询", " ", "sales_query"));

        AgentToolCatalog.ToolCatalogFingerprint before = catalogFor(nodes,
                List.of(executor("sales_query", "服务端描述 v1"))).resolve(KNOWLEDGE_ONLY).fingerprint();
        AgentToolCatalog.ToolCatalogFingerprint after = catalogFor(nodes,
                List.of(executor("sales_query", "服务端描述 v2"))).resolve(KNOWLEDGE_ONLY).fingerprint();

        assertThat(before).isNotEqualTo(after);
    }

    /**
     * 只读与确认互不影响，各自都要进指纹
     */
    @Test
    void shouldCountReadOnlyAndConfirmSeparatelyIntoFingerprint() {
        AgentToolCatalog.McpToolFingerprint undeclared = fingerprintOf(false, null);
        AgentToolCatalog.McpToolFingerprint readOnly = fingerprintOf(false, readOnlyHint(true));
        AgentToolCatalog.McpToolFingerprint readOnlyConfirmed = fingerprintOf(true, readOnlyHint(true));

        assertThat(undeclared.needsConfirm()).isTrue();
        assertThat(readOnly.needsConfirm()).isFalse();
        assertThat(undeclared).isNotEqualTo(readOnly);
        assertThat(readOnly.readOnly()).isEqualTo(readOnlyConfirmed.readOnly());
        assertThat(readOnly).isNotEqualTo(readOnlyConfirmed);
    }

    /**
     * 指纹不能跟着意图树的读出顺序漂
     */
    @Test
    void shouldResolveSameFingerprintRegardlessOfNodeOrder() {
        IntentNode sales = mcpNode("a_sales", "销售查询", "查销售", "sales_query");
        IntentNode report = mcpNode("b_report", "销售报表", "查报表", "sales_report");
        List<RemoteTool> executors = List.of(
                executor("sales_query", "服务端描述"), executor("sales_report", "服务端描述"));

        AgentToolCatalog.ResolvedCatalog ordered = catalogFor(List.of(sales, report), executors).resolve(KNOWLEDGE_ONLY);
        AgentToolCatalog.ResolvedCatalog shuffled = catalogFor(List.of(report, sales), executors).resolve(KNOWLEDGE_ONLY);

        assertThat(ordered.displayNameOf("sales_query")).isEqualTo("销售查询");
        assertThat(ordered.fingerprint()).isEqualTo(shuffled.fingerprint());
    }

    private AgentToolCatalog.McpToolFingerprint fingerprintOf(boolean confirmConfigured, ToolAnnotations annotations) {
        return McpToolBinding.of("tool", "工具", "描述", confirmConfigured, executor("tool", "服务端描述", annotations))
                .fingerprint();
    }

    private PermissionBehavior behaviorOf(Toolkit toolkit, String toolId) {
        ToolBase tool = (ToolBase) toolkit.getTool(toolId);
        return tool.checkPermissions(Map.of(), null).block().getBehavior();
    }

    private Toolkit buildToolkitFor(RemoteTool... executors) {
        List<RemoteTool> executorList = List.of(executors);
        List<IntentNode> nodes = executorList.stream()
                .map(executor -> mcpNode(
                        executor.definition().name(), executor.definition().name(),
                        "意图树描述", executor.definition().name()))
                .toList();
        AgentToolCatalog catalog = catalogFor(nodes, executorList);
        return catalog.buildToolkit(catalog.resolve(KNOWLEDGE_ONLY));
    }

    private AgentToolCatalog catalogFor(List<IntentNode> nodes, List<RemoteTool> executors) {
        IntentNodeRegistry intentNodeRegistry = mock(IntentNodeRegistry.class);
        when(intentNodeRegistry.listMcpToolNodes()).thenReturn(nodes);
        AgentMcpClients mcpClients = mock(AgentMcpClients.class);
        executors.forEach(executor -> when(mcpClients.get(executor.definition().name())).thenReturn(executor));

        return new AgentToolCatalog(
                mock(KnowledgeSearchFacade.class),
                intentNodeRegistry,
                mcpClients,
                memoryProperties(false),
                mock(AgentMemoryFlusher.class),
                mock(AgentSkillRegistry.class));
    }

    /**
     * 长期记忆挂载与卸载都由开关和槽位决定，两者都要能改变指纹
     */
    private AgentToolCatalog catalogWithMemory(boolean longTermEnabled) {
        IntentNodeRegistry intentNodeRegistry = mock(IntentNodeRegistry.class);
        when(intentNodeRegistry.listMcpToolNodes()).thenReturn(List.of());
        AgentMcpClients mcpClients = mock(AgentMcpClients.class);

        return new AgentToolCatalog(
                mock(KnowledgeSearchFacade.class),
                intentNodeRegistry,
                mcpClients,
                memoryProperties(longTermEnabled),
                mock(AgentMemoryFlusher.class),
                mock(AgentSkillRegistry.class));
    }

    private Map<String, String> promptsWithMemory(String memorySlotContent) {
        return Map.of(
                AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION.name(), "当前 Agent 的知识库工具描述",
                AgentPromptSlot.AGENT_MEMORY_TOOL_DESCRIPTION.name(), memorySlotContent);
    }

    private AgentMemoryProperties memoryProperties(boolean longTermEnabled) {
        AgentMemoryProperties properties = new AgentMemoryProperties();
        properties.setLongTermEnabled(longTermEnabled);
        return properties;
    }

    private ToolAnnotations readOnlyHint(boolean readOnly) {
        return new ToolAnnotations(null, readOnly, null, null, null, null);
    }

    private IntentNode mcpNode(String id, String name, String description, String toolId) {
        return IntentNode.builder()
                .id(id)
                .name(name)
                .description(description)
                .kind(IntentKind.MCP)
                .mcpToolId(toolId)
                .build();
    }

    private RemoteTool executor(String toolId, String description) {
        return executor(toolId, description, null);
    }

    private RemoteTool executor(String toolId, String description, ToolAnnotations annotations) {
        JsonSchema schema = new JsonSchema("object", Map.of(), List.of(), false, null, null);
        return executor(Tool.builder()
                .name(toolId)
                .description(description)
                .inputSchema(schema)
                .annotations(annotations)
                .build());
    }

    private RemoteTool executor(Tool tool) {
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn("default");
        return new RemoteTool(tool, client);
    }
}

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

package com.nageoffer.ai.ragent.rag.core.prompt;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文格式化器，负责将知识库检索结果和 MCP 工具调用结果格式化为可嵌入 Prompt 的文本
 */
public interface ContextFormatter {

    /**
     * 格式化知识库检索上下文
     *
     * @param kbIntents         知识库意图节点及其得分列表
     * @param eligibleIntentIds 允许注入回答规则的意图 ID
     * @param rerankedChunks    后处理后的有序文档块
     * @param contextTopK       最终进 LLM 的文档块条数上限（检索预算的 contextTopK 段）
     * @return 格式化后的知识库上下文文本
     */
    String formatKbContext(List<NodeScore> kbIntents,
                           Set<String> eligibleIntentIds,
                           List<RetrievedChunk> rerankedChunks,
                           int contextTopK);

    /**
     * 格式化 MCP 工具调用上下文
     *
     * @param toolResults MCP 工具调用结果，按工具名称分组
     * @param mcpIntents  MCP 意图节点及其得分列表
     * @return 格式化后的 MCP 上下文文本
     */
    String formatMcpContext(Map<String, List<CallToolResult>> toolResults, List<NodeScore> mcpIntents);
}

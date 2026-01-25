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

package com.nageoffer.ai.ragent.rag.constant;

/**
 * RAG 系统常量类
 *
 * <p>
 * 定义 RAG（Retrieval-Augmented Generation）系统中使用的各种常量配置，包括不限于：
 * <ul>
 *   <li>意图识别相关阈值和限制</li>
 *   <li>查询改写提示词模板</li>
 *   <li>RAG 问答提示词模板</li>
 *   <li>系统对话提示词模板</li>
 * </ul>
 * </p>
 *
 * <p>
 * 这些常量主要用于控制 RAG 系统的行为和生成质量，包括意图过滤、查询优化、
 * 文档检索和智能问答等核心流程
 * </p>
 */
public class RAGConstant {

    /**
     * 意图识别最低分数阈值
     * <p>
     * 低于这个分数就当成"聊偏了"，不参与 RAG 检索流程
     * </p>
     */
    public static final double INTENT_MIN_SCORE = 0.35;

    /**
     * 单次查询最多参与的意图数量上限
     * 防止拉取过多 Collection 导致性能问题
     */
    public static final int MAX_INTENT_COUNT = 3;

    /**
     * Rerank 分数过滤的边际比率（相对于最高分）
     */
    public static final double SCORE_MARGIN_RATIO = 0.75;

    /**
     * 默认返回的 TopK
     */
    public static final int DEFAULT_TOP_K = 5;

    /**
     * 检索时的 TopK 扩展倍数
     */
    public static final int SEARCH_TOP_K_MULTIPLIER = 3;

    /**
     * 检索时的最小 TopK
     */
    public static final int MIN_SEARCH_TOP_K = 20;

    /**
     * Rerank 限制倍数
     */
    public static final int RERANK_LIMIT_MULTIPLIER = 2;

    /**
     * 意图识别提示词模板路径（串行模式）
     * 一次性发送所有意图节点给 LLM 进行识别
     */
    public static final String INTENT_CLASSIFIER_PROMPT_PATH = "prompt/intent-classifier.st";

    /**
     * 引导式问答提示词模板路径
     * 用于生成引导式问答的选项提示内容
     */
    public static final String GUIDANCE_PROMPT_PATH = "prompt/guidance-prompt.st";

    /**
     * 系统对话提示词模板路径
     * 定义企业知识助手「小码」的角色设定和对话规则，包括打招呼、自我介绍、问题分类处理等场景。模板通过 {@code {question}} 占位符接收用户问题。
     */
    public static final String CHAT_SYSTEM_PROMPT_PATH = "prompt/answer-chat-system.st";

    /**
     * 查询改写 + 多问句拆分提示词模板路径
     * 要求同时返回改写后的单条查询和子问题列表
     */
    public static final String QUERY_REWRITE_AND_SPLIT_PROMPT_PATH = "prompt/user-question-rewrite.st";

    /**
     * 对话记忆压缩提示词模板路径
     * 通过 {@code {summary_max_chars}} 控制摘要长度上限
     */
    public static final String CONVERSATION_SUMMARY_PROMPT_PATH = "prompt/conversation-summary.st";

    /**
     * 会话标题生成提示词模板路径
     * 通过 {@code {title_max_chars}} 与 {@code {question}} 控制标题长度与输入问题
     */
    public static final String CONVERSATION_TITLE_PROMPT_PATH = "prompt/conversation-title.st";

    /**
     * 默认 RAG 问答提示词模板路径
     * 用于指导大模型基于检索到的文档内容进行准确回答，包含严格的事实性约束和链接处理规则
     */
    public static final String RAG_ENTERPRISE_PROMPT_PATH = "prompt/answer-chat-kb.st";

    /**
     * MCP 工具参数提取提示词模板路径
     * 用于从用户问题中提取工具调用参数
     */
    public static final String MCP_PARAMETER_EXTRACT_PROMPT_PATH = "prompt/mcp-parameter-extract.st";

    /**
     * MCP-only 场景提示词模板路径
     * 仅动态数据片段时使用
     */
    public static final String MCP_ONLY_PROMPT_PATH = "prompt/answer-chat-mcp.st";

    /**
     * MCP + KB 混合场景提示词模板路径
     * 兼顾动态数据片段与知识库内容的综合回答
     */
    public static final String MCP_KB_MIXED_PROMPT_PATH = "prompt/answer-chat-mcp-kb-mixed.st";
}

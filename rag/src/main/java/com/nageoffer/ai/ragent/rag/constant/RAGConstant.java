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
 *   <li>......</li>
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
     * 多通道检索占位符键
     * <p>
     * 当没有意图识别结果时，使用此键作为 intentChunks Map 的占位符
     * 实际处理时只使用 Map 的 values，不关心具体的 key 值
     * </p>
     */
    public static final String MULTI_CHANNEL_KEY = "multi_channel";

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
     * 歧义确认提示词模板路径
     * 用于边界 case 时调 LLM 二次确认是否存在品类歧义
     */
    public static final String GUIDANCE_AMBIGUITY_CHECK_PROMPT_PATH = "prompt/guidance-ambiguity-check.st";


    /**
     * 查询改写 + 多问句拆分提示词模板路径
     * 要求同时返回改写后的单条查询和子问题列表
     */
    public static final String QUERY_REWRITE_AND_SPLIT_PROMPT_PATH = "prompt/user-question-rewrite.st";


    /**
     * 会话标题生成提示词模板路径
     * 通过 {@code {title_max_chars}} 与 {@code {question}} 控制标题长度与输入问题
     */
    public static final String CONVERSATION_TITLE_PROMPT_PATH = "prompt/conversation-title.st";


    /**
     * 知识资料回答的行内引用规则
     * <p>
     * 仅在 {@code rag.citation.enabled=true} 且存在知识库上下文时，由 Prompt 编排层追加
     */
    public static final String ANSWER_CITATION_RULES_PROMPT_PATH = "prompt/answer-citation-rules.st";

    /**
     * MCP 工具参数提取提示词模板路径
     * 用于从用户问题中提取工具调用参数
     */
    public static final String MCP_PARAMETER_EXTRACT_PROMPT_PATH = "prompt/mcp-parameter-extract.st";

    /**
     * MCP 工具参数提取用户消息提示词模板路径
     * 用于构建包含工具定义和用户问题的用户消息，通过 {@code {tool_definition}} 和 {@code {user_question}} 占位符注入内容
     */
    public static final String MCP_PARAMETER_EXTRACT_USER_PROMPT_PATH = "prompt/mcp-parameter-extract-user.st";




    // ==================== 上下文格式化模板（单文件多 section） ====================

    /**
     * 上下文格式化模板文件路径
     * <p>
     * 包含所有上下文格式化所需的 section，通过 {@code --- section: name ---} 分隔，
     * 使用 {@code PromptTemplateLoader.renderSection(path, section, slots)} 渲染
     */
    public static final String CONTEXT_FORMAT_PATH = "prompt/context-format.st";
}

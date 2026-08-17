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

/**
 * Prompt 构建场景枚举，根据检索来源（知识库 / MCP）确定系统提示词模板
 */
public enum PromptScene {

    /**
     * 仅命中知识库检索，使用企业知识库专用提示词模板
     */
    KB_ONLY,

    /**
     * 仅命中 MCP 工具调用，使用 MCP 专用提示词模板
     */
    MCP_ONLY,

    /**
     * 同时命中知识库和 MCP，使用混合提示词模板
     */
    MIXED,

    /**
     * 无任何检索命中，返回空提示词
     */
    EMPTY
}

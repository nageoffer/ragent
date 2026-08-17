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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * Prompt 构建上下文，封装一次 RAG 请求中用于组装提示词的全部输入数据
 */
@Data
@Builder
public class PromptContext {

    /**
     * 用户原始问题
     */
    private String question;

    /**
     * MCP 工具调用返回的上下文文本（已格式化）
     */
    private String mcpContext;

    /**
     * 知识库检索返回的上下文文本（已格式化）
     */
    private String kbContext;

    /**
     * MCP 通道命中的意图及其得分列表
     */
    private List<NodeScore> mcpIntents;

    /**
     * 知识库通道命中的意图及其得分列表
     */
    private List<NodeScore> kbIntents;

    /**
     * 允许参与模板选择和规则注入的意图 ID
     */
    @Builder.Default
    private Set<String> eligibleIntentIds = Set.of();

    /**
     * 是否包含 MCP 上下文
     */
    public boolean hasMcp() {
        return StrUtil.isNotBlank(mcpContext);
    }

    /**
     * 是否包含知识库上下文
     */
    public boolean hasKb() {
        return StrUtil.isNotBlank(kbContext);
    }
}

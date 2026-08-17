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

package com.nageoffer.ai.ragent.rag.core.intent;

import cn.hutool.core.util.StrUtil;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * NodeScore 过滤工具类
 * 统一 KB / MCP 意图的过滤逻辑，避免多处重复定义
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class NodeScoreFilters {

    /**
     * 过滤 MCP 类型意图（node 非空、kind=MCP、mcpToolId 非空）
     * <p>
     * 注意：不做 score 下限过滤，调用方应确保输入已经过 INTENT_MIN_SCORE 筛选
     */
    public static List<NodeScore> mcp(List<NodeScore> scores) {
        return scores.stream()
                .filter(ns -> ns.getNode() != null && ns.getNode().isMCP())
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getMcpToolId()))
                .toList();
    }

    /**
     * 过滤 KB 类型意图（node 非空、kind 为 null 或 KB）
     * <p>
     * 注意：不做 score 下限过滤，调用方应确保输入已经过 INTENT_MIN_SCORE 筛选
     */
    public static List<NodeScore> kb(List<NodeScore> scores) {
        return scores.stream()
                .filter(ns -> ns.getNode() != null && ns.getNode().isKB())
                .toList();
    }

    /**
     * 过滤 KB 类型意图并限制最低分数
     */
    public static List<NodeScore> kb(List<NodeScore> scores, double minScore) {
        return scores.stream()
                .filter(ns -> ns.getScore() >= minScore)
                .filter(ns -> ns.getNode() != null && ns.getNode().isKB())
                .toList();
    }

    /**
     * 提取 KB 意图对应的 collection 名称（去空、去重）
     * <p>
     * 供关键词 / 图谱等通道统一「意图域」选库，避免多处重复该映射
     */
    public static List<String> kbCollections(List<NodeScore> scores) {
        return kb(scores).stream()
                .flatMap(ns -> ns.getNode().getEffectiveCollectionNames().stream())
                .distinct()
                .toList();
    }
}

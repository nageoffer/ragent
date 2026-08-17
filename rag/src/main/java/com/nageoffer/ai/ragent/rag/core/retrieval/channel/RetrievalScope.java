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

package com.nageoffer.ai.ragent.rag.core.retrieval.channel;

import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索作用域
 * <p>
 * 每个子问题算一次、向量 / 关键词 / 图谱共读一份：KB 意图足够置信则收窄到命中库（定向），否则退化为全库（全局）
 * 定向下 {@code supplementCollections} 是未命中库，向量通道用它并行补一路，兜住意图判错导致的漏召回
 *
 * @param directed              是否收窄到命中库
 * @param topScore              KB 意图最高分，仅用于观测与阈值校准
 * @param intents               命中的 KB 意图，定向时非空
 * @param targetCollections     主检索范围：定向为命中且有效的库（绑定集与知识库表求交），全局为全部有效库
 * @param supplementCollections 补充检索范围：全部有效库减去命中库，全局作用域下恒为空
 */
public record RetrievalScope(boolean directed,
                             double topScore,
                             List<NodeScore> intents,
                             List<String> targetCollections,
                             List<String> supplementCollections) {

    public Set<String> directedIntentIds() {
        if (!directed) {
            return Set.of();
        }
        Set<String> intentIds = new LinkedHashSet<>();
        for (NodeScore intent : intents) {
            if (intent == null || intent.getNode() == null) {
                continue;
            }
            String intentId = intent.getNode().getId();
            if (intentId == null || intentId.isBlank()) {
                continue;
            }
            intentIds.add(intentId);
        }
        return Set.copyOf(intentIds);
    }

    /**
     * 全局作用域：不收窄，无补充路
     */
    public static RetrievalScope global(double topScore, List<String> activeCollections) {
        return new RetrievalScope(false, topScore, List.of(), activeCollections, List.of());
    }
}

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

package com.nageoffer.ai.ragent.rag.core.retrieval;

/**
 * 检索漏斗的三段预算
 * <p>
 * 一条 {@code retrieve → fuse → rerank → render} 链路本有三个方向与成本各异、须各自独立的预算，
 * 以往被一个 {@code topK} 复用（且被意图节点 topK 的 max 进一步耦合），调一处误动三处。
 * 这里显式拆成三段、一次算好、各阶段只读属于自己的那一段，杜绝「一个 int 三义」：
 * <ul>
 *   <li>{@code recallBudget}   — 每通道 fan-out 基数（想大、保召回；各通道再乘自身倍率）</li>
 *   <li>{@code candidateLimit} — 融合后送 Rerank 的候选池上限（成本天花板）</li>
 *   <li>{@code contextTopK}    — 最终进 LLM 的条数（想小而精，即产品语义的 topK）</li>
 * </ul>
 * 漏斗单调收窄的不变式 {@code recallBudget ≥ contextTopK} 且 {@code candidateLimit ≥ contextTopK}
 * 由配置侧启动校验兜底（见 {@code SearchChannelProperties}）
 */
public record RetrievalBudget(int recallBudget, int candidateLimit, int contextTopK) {

    /**
     * 三段同值构造：用于测试或无需区分预算的平凡场景
     */
    public static RetrievalBudget uniform(int k) {
        return new RetrievalBudget(k, k, k);
    }
}

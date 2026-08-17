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

import java.util.List;

/**
 * 主路与补充路的候选名额划分
 * <p>
 * 三条通道共用这一条规则：各自把自己的产出额度按同一比例切一片给未命中库
 * 补充证据必须有固定名额而非与命中库证据自由竞争——意图判错时正确证据只在未命中库里，
 * 拼相关度分必然抢不过命中库里那些「表面很像但答非所问」的证据
 * 名额兑现到通道出口为止：入列有保证，能否活过下游融合截断不在此处承诺
 * <p>
 * 两个字段恒为名额语义，不设「非正表示不封顶」的哨兵：名额与哨兵共用取值空间时，
 * 「补充路 0 名额」会被读成「补充路不封顶」，配置意图与实际执行正好相反
 *
 * @param primary    主路候选名额，补充生效时恒 >= 1
 * @param supplement 补充路候选名额，0 表示本次不补
 */
public record ScopeQuota(int primary, int supplement) {

    /**
     * 按作用域切分通道产出额度
     * 全局作用域、无未命中库（如单库部署）、比例置零、无额度可分，四种情况都不补，额度全归主路
     *
     * @param budget          本通道的产出额度
     * @param supplementRatio 划给补充路的比例
     */
    public static ScopeQuota split(RetrievalScope scope, int budget, double supplementRatio) {
        if (!scope.directed() || scope.supplementCollections().isEmpty() || supplementRatio <= 0 || budget <= 0) {
            return new ScopeQuota(budget, 0);
        }
        // 夹在 [budget-1 与 1 之间] 一次兜住两端：上界保证主路至少留 1 个名额——被挤成 0 意味着高置信命中库
        // 一条都不召回，与「定向优先、补充兜底」完全相反；额度只剩 1 时上界自然收到 0，退化为不补
        int supplement = Math.min(budget - 1, Math.max(1, (int) Math.round(budget * supplementRatio)));
        return new ScopeQuota(budget - supplement, supplement);
    }

    /**
     * 按名额截断已按相关性降序的候选，名额为 0 即取零条
     */
    public static <T> List<T> cap(List<T> chunks, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return chunks.size() > limit ? List.copyOf(chunks.subList(0, limit)) : chunks;
    }
}

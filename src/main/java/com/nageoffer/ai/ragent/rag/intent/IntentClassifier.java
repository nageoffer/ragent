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

package com.nageoffer.ai.ragent.rag.intent;

import java.util.List;

/**
 * 意图分类器接口
 * <p>
 * 支持两种实现策略：
 * <ul>
 *     <li>串行分类：所有意图在单次 LLM 调用中完成识别（适用于意图数量较少场景）</li>
 *     <li>并行分类：按 Domain 拆分意图，并行调用多个 LLM 完成识别（适用于意图数量多场景）</li>
 * </ul>
 */
public interface IntentClassifier {

    /**
     * 对所有叶子分类节点做意图识别
     *
     * @param question 用户问题
     * @return 按 score 从高到低排序的节点打分列表
     */
    List<NodeScore> classifyTargets(String question);

    /**
     * 取前 topN 个且 score >= minScore 的分类
     *
     * @param question 用户问题
     * @param topN     最多返回 N 个结果
     * @param minScore 最低分数阈值
     * @return 过滤后的节点打分列表
     */
    default List<NodeScore> topKAboveThreshold(String question, int topN, double minScore) {
        return classifyTargets(question).stream()
                .filter(ns -> ns.getScore() >= minScore)
                .limit(topN)
                .toList();
    }
}

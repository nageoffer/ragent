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

package com.nageoffer.ai.ragent.rag.config;

import com.nageoffer.ai.ragent.rag.constant.RAGConstant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 检索预算三段值 {@code recallBudget / candidateLimit / contextTopK} 的解析与漏斗不变式校验
 */
class SearchChannelPropertiesTest {

    @Test
    @DisplayName("recallBudget 默认取绝对值，=0 时回退 contextTopK，>0 用显式值")
    void resolveRecallBudget() {
        SearchChannelProperties props = new SearchChannelProperties();
        assertEquals(20, props.resolveRecallBudget(10), "默认 recall-budget=20 为绝对每通道召回条数");

        props.setRecallBudget(0);
        assertEquals(10, props.resolveRecallBudget(10), "recall-budget=0 应回退到 contextTopK 作兜底守卫");

        props.setRecallBudget(30);
        assertEquals(30, props.resolveRecallBudget(10), "显式 recall-budget 应优先");
    }

    @Test
    @DisplayName("默认配置满足漏斗不变式，启动不抛异常")
    void defaultConfigPassesInvariant() {
        assertDoesNotThrow(() -> new SearchChannelProperties().afterPropertiesSet());
    }

    @Test
    @DisplayName("recallBudget < contextTopK 破坏不变式，启动即抛")
    void recallBudgetBelowContextTopKThrows() {
        SearchChannelProperties props = new SearchChannelProperties();
        props.setRecallBudget(5);      // < default-top-k=10
        assertThrows(IllegalStateException.class, props::afterPropertiesSet);
    }

    @Test
    @DisplayName("candidateLimit < contextTopK 破坏不变式，启动即抛")
    void candidateLimitBelowContextTopKThrows() {
        SearchChannelProperties props = new SearchChannelProperties();
        props.getFusion().setRerankCandidateLimit(5);  // < default-top-k=10
        assertThrows(IllegalStateException.class, props::afterPropertiesSet);
    }

    @Test
    @DisplayName("candidateLimit<=0 表示不截断，不参与不变式校验")
    void candidateLimitUnboundedSkipsInvariant() {
        SearchChannelProperties props = new SearchChannelProperties();
        props.getFusion().setRerankCandidateLimit(0);  // 不截断
        assertDoesNotThrow(props::afterPropertiesSet);
    }

    @Test
    @DisplayName("minIntentScore 低于上游意图过滤下限时不会生效，启动即抛")
    void minIntentScoreBelowUpstreamGateThrows() {
        SearchChannelProperties props = new SearchChannelProperties();
        props.getScope().setMinIntentScore(RAGConstant.INTENT_MIN_SCORE - 0.05);
        assertThrows(IllegalStateException.class, props::afterPropertiesSet);

        props.getScope().setMinIntentScore(RAGConstant.INTENT_MIN_SCORE);
        assertDoesNotThrow(props::afterPropertiesSet, "等于下限时该配置仍有意义，不应拦截");
    }

    @Test
    @DisplayName("confidenceThreshold 不高于 minIntentScore 或大于 1 时有一整条分支变死代码，启动即抛")
    void confidenceThresholdOutsideGateRangeThrows() {
        SearchChannelProperties props = new SearchChannelProperties();
        props.getScope().setConfidenceThreshold(props.getScope().getMinIntentScore());
        assertThrows(IllegalStateException.class, props::afterPropertiesSet,
                "意图分已被 minIntentScore 过滤过一道，阈值不高于它则「低置信退化为全局」永不触发");

        props.getScope().setConfidenceThreshold(1.5);
        assertThrows(IllegalStateException.class, props::afterPropertiesSet,
                "意图分按 0~1 输出，阈值大于 1 则定向路与补充路一起不可达");
    }

    @Test
    @DisplayName("supplementRatio 取满 1 会把主路名额清零，启动即抛")
    void supplementRatioAtOrAboveOneThrows() {
        SearchChannelProperties props = new SearchChannelProperties();
        props.getScope().setSupplementRatio(1.0);
        assertThrows(IllegalStateException.class, props::afterPropertiesSet);

        props.getScope().setSupplementRatio(0);
        assertDoesNotThrow(props::afterPropertiesSet, "置零是关闭补充路的回退路径，不应拦截");
    }
}

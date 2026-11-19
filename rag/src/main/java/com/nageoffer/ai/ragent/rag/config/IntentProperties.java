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

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 意图识别策略配置
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.intent")
public class IntentProperties implements InitializingBean {

    /**
     * 分类策略，llm 或 jev，默认 llm 与原部署一致
     * <p>
     * 分类器由 {@code @ConditionalOnProperty} 按本值挑选，不做 trim：Spring 的条件匹配同样不 trim，
     * 两边规则不一致会让带空格的值校验通过却静默跑 llm
     */
    private String type = "llm";

    /**
     * jev 策略专有配置
     */
    private Jev jev = new Jev();

    @Override
    public void afterPropertiesSet() {
        if (!StrUtil.equalsAnyIgnoreCase(type, "llm", "jev")) {
            throw new IllegalStateException("RAG 意图识别仅支持 llm / jev，当前值: " + type);
        }
        log.info("意图分类策略: {}", StrUtil.lowerFirst(type));
    }

    @Data
    public static class Jev {

        /**
         * Noul 概率下限，低于此值的候选不进入意图列表
         * <p>
         * 与 {@code rag.search.scope.min-intent-score} 对齐：低于该值的 KB 意图既不能贡献检索库、
         * 也不参与收窄判定，放进来只会污染归因。下游还有两道过滤不受本值影响——
         * {@code IntentResolver} 按 {@code INTENT_MIN_SCORE} 与 {@code MAX_INTENT_COUNT} 截断，
         * 作用域收窄另看 {@code rag.search.scope.confidence-threshold}
         */
        private double minNoul = 0.4;
    }
}

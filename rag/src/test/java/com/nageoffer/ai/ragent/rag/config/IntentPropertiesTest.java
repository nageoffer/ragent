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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 策略取值的校验契约
 * <p>
 * 分类器由 {@code @ConditionalOnProperty} 挑选，它比较值时不 trim，所以这里也不能 trim
 */
class IntentPropertiesTest {

    @Test
    @DisplayName("默认 llm，与原部署一致")
    void defaultsToLlm() {
        assertEquals("llm", new IntentProperties().getType());
    }

    @Test
    @DisplayName("llm 与 jev 都合法，大小写不敏感")
    void acceptsBothStrategies() {
        assertDoesNotThrow(() -> validate("llm"));
        assertDoesNotThrow(() -> validate("jev"));
        assertDoesNotThrow(() -> validate("JEV"));
    }

    @Test
    @DisplayName("认不出的值启动即报错，不静默回落")
    void rejectsUnknownStrategy() {
        assertEquals("RAG 意图识别仅支持 llm / jev，当前值: auto",
                assertThrows(IllegalStateException.class, () -> validate("auto")).getMessage());
        assertThrows(IllegalStateException.class, () -> validate(null));
    }

    @Test
    @DisplayName("带空格的值判为非法：Spring 的条件匹配不 trim，放行会让它静默跑 llm")
    void rejectsPaddedValue() {
        assertThrows(IllegalStateException.class, () -> validate(" jev "));
    }

    private void validate(String type) {
        IntentProperties properties = new IntentProperties();
        properties.setType(type);
        properties.afterPropertiesSet();
    }
}

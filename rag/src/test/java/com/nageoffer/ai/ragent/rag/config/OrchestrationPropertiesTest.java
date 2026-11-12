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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 只覆盖档位解析
 * <p>
 * 认不出的取值由 {@link OrchestrationMode#of} 回落 WORKFLOW，这里不加启动期校验拦它：档位配错在第一屏
 * 就现形（EngineGate 会渲染 v1 聊天页而不是 Agent 页），再早报几秒换不来什么
 */
class OrchestrationPropertiesTest {

    @Test
    void defaultTypeIsWorkflow() {
        assertEquals(OrchestrationMode.WORKFLOW, new OrchestrationProperties().getMode());
    }

    @Test
    void acceptsKnownModesIgnoringCaseAndPadding() {
        assertEquals(OrchestrationMode.AGENT, of("agent").getMode());
        assertEquals(OrchestrationMode.AGENT, of("AGENT").getMode());
        assertEquals(OrchestrationMode.AGENT, of(" agent ").getMode());
        assertEquals(OrchestrationMode.WORKFLOW, of("Workflow").getMode());
    }

    private OrchestrationProperties of(String type) {
        OrchestrationProperties properties = new OrchestrationProperties();
        properties.setType(type);
        return properties;
    }
}

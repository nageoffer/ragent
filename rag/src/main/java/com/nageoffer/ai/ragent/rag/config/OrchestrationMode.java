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

/**
 * 执行架构档位，由 ragent.engine.type 指定
 * <p>
 * 属部署级决策（切换需重启，且 AGENT 依赖外部 ReAct 服务存活），因此不开放后台切换
 */
public enum OrchestrationMode {

    /**
     * v1 编排管线：意图分类 → 检索 → 合成，链路确定、延迟低
     */
    WORKFLOW,

    /**
     * v2 ReAct 架构：主 Agent 决策，RAG 管线降级为其中一个 Tool
     */
    AGENT;

    /**
     * 解析配置值，大小写不敏感，无法识别时回落 WORKFLOW
     */
    public static OrchestrationMode of(String value) {
        if (StrUtil.isBlank(value)) {
            return WORKFLOW;
        }
        for (OrchestrationMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return WORKFLOW;
    }
}

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

package com.nageoffer.ai.ragent.agent.controller.vo;

import java.util.List;

/**
 * Agent 引擎身份视图，供前端点亮状态徽标与框架信息块，只回非敏感信息
 *
 * @param framework     执行框架标识
 * @param model         当前 Chat 模型名
 * @param maxIters      单轮 ReAct 迭代上限
 * @param capabilities  引擎能力清单
 * @param toolProvider  工具提供方
 * @param mcpConfigured AgentScope 客户端是否发现可用工具
 */
public record AgentMetaVO(
        String framework,
        String model,
        Integer maxIters,
        List<String> capabilities,
        String toolProvider,
        boolean mcpConfigured
) {
}

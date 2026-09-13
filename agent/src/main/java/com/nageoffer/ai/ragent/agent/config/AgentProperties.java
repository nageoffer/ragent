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

package com.nageoffer.ai.ragent.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 执行架构顶级配置（agent: 段，与 rag / ai 平级）
 * 单模型无 fallback：chat.provider 引用 ai.providers 解析 url / api-key / endpoints.chat
 */
@Data
@Configuration 
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private Chat chat = new Chat();

    /**
     * ReAct 循环上限，超出后由框架熔断收尾
     */
    private Integer maxIters = 10;

    /**
     * 单次模型调用失败重试次数
     */
    private Integer maxRetries = 2;

    /**
     * SSE 通道超时，到点即回收上游运行；一次 Agent 运行最多 max-iters 轮，每轮量级接近 RAG 单问全程
     */
    private Long sseTimeoutMs = 900_000L;   // 15 分钟

    @Data
    public static class Chat {  

        /**
         * ai.providers 下的供应商 key
         */
        private String provider;

        /**
         * 直接传给 OpenAI 兼容端点的模型名
         */
        private String model;
    }
}

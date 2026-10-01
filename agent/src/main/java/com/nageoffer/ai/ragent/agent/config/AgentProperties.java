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

import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Agent 执行架构顶级配置（agent: 段，与 rag / ai 平级）
 * 单模型无 fallback：chat.provider 引用 ai.providers 解析 url / api-key / endpoints.chat
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent")
@Validated
public class AgentProperties {

    private Chat chat = new Chat();

    /**
     * ReAct 循环上限，超出后由框架熔断收尾
     */
    private Integer maxIters = 10;

    /**
     * 单次模型调用的最大尝试次数（含首次），1 即不重试
     * 重试加在整条流之上，半程失败会重订阅，已吐出的正文不回滚
     */
    private Integer maxRetries = 1;

    /**
     * SSE 通道超时
     */
    @NotNull
    private Long sseTimeoutMs = 900_000L;

    /**
     * 同一用户同时运行的会话数
     */
    @NotNull
    @Min(1)
    private Integer maxConcurrentRunsPerUser = 5;

    @AssertTrue(message = "agent.sse-timeout-ms 必须小于本地任务保留时间")
    public boolean isSseTimeoutWithinTaskRetention() {
        return sseTimeoutMs == null || sseTimeoutMs < StreamTaskManager.taskRetention().toMillis();
    }

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

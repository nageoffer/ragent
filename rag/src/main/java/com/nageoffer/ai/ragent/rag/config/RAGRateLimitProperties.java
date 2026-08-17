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

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 全局并发限流配置
 */
@Data
@Configuration
public class RAGRateLimitProperties {

    /**
     * 是否启用全局限流
     */
    @Value("${rag.rate-limit.global.enabled:true}")
    private Boolean globalEnabled;

    /**
     * 最大并发数
     */
    @Value("${rag.rate-limit.global.max-concurrent:50}")
    private Integer globalMaxConcurrent;

    /**
     * 最大等待秒数
     */
    @Value("${rag.rate-limit.global.max-wait-seconds:20}")
    private Integer globalMaxWaitSeconds;

    /**
     * 许可自动释放时间（兜底用），单位秒
     */
    @Value("${rag.rate-limit.global.lease-seconds:600}")
    private Integer globalLeaseSeconds;

    /**
     * 排队轮询间隔（毫秒）
     */
    @Value("${rag.rate-limit.global.poll-interval-ms:200}")
    private Integer globalPollIntervalMs;
}

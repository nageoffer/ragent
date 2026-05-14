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

import com.nageoffer.ai.ragent.rag.service.ratelimit.FairDistributedRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SSE 聊天全局限流器 bean 装配
 */
@Configuration
public class ChatRateLimiterConfig {

    private static final String CHAT_LIMITER_NAME = "rag:global:chat";

    @Bean(initMethod = "start", destroyMethod = "stop")
    public FairDistributedRateLimiter chatRateLimiter(RedissonClient redissonClient,
                                                      RAGRateLimitProperties rateLimitProperties) {
        return new FairDistributedRateLimiter(
                CHAT_LIMITER_NAME,
                redissonClient,
                rateLimitProperties::getGlobalMaxConcurrent,
                rateLimitProperties::getGlobalLeaseSeconds,
                rateLimitProperties::getGlobalPollIntervalMs
        );
    }
}

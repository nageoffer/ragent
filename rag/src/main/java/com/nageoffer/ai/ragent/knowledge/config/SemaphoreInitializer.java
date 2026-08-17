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

package com.nageoffer.ai.ragent.knowledge.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 文档上传信号量初始化器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemaphoreInitializer {

    private final RedissonClient redissonClient;
    private final RagSemaphoreProperties semaphoreProperties;

    @PostConstruct
    public void documentUploadSemaphoreInitialize() {
        RagSemaphoreProperties.PermitExpirableConfig config = semaphoreProperties.getDocumentUpload();
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(config.getName());

        semaphore.setPermits(config.getMaxConcurrent());
        log.info("Initialized document upload semaphore: name={}, maxConcurrent={}",
                config.getName(),
                config.getMaxConcurrent());
    }
}

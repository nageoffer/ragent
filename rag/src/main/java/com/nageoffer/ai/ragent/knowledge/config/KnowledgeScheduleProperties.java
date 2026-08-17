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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 知识库定时任务配置
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "rag.knowledge.schedule")
public class KnowledgeScheduleProperties {

    /**
     * 定时扫描间隔（毫秒）
     */
    private Long scanDelayMs = 10000L;

    /**
     * 分布式锁持有时长（秒）
     */
    private Long lockSeconds = 900L;

    /**
     * 每次扫描批量大小
     */
    private Integer batchSize = 20;

    /**
     * 定时拉取最小间隔（秒）
     */
    private Long minIntervalSeconds = 60L;

    /**
     * RUNNING 状态超时阈值（分钟），超过此时间未完成的文档重置为 FAILED
     */
    private Long runningTimeoutMinutes = 30L;
}

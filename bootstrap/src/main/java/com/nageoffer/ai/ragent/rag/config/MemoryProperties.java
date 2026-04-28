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

import com.nageoffer.ai.ragent.rag.config.validation.ValidMemoryConfig;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 记忆配置属性。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.memory")
@Validated
@ValidMemoryConfig
public class MemoryProperties {

    @Min(1)
    @Max(100)
    private Integer historyKeepTurns = 8;

    @Min(1)
    @Max(100)
    private Integer workingKeepTurns = 8;

    @Min(1)
    @Max(1440)
    private Integer workingCacheTtlMinutes = 30;

    private Boolean summaryEnabled = false;

    @Min(1)
    @Max(200)
    private Integer summaryStartTurns = 9;

    @Min(50)
    @Max(1000)
    private Integer summaryMaxChars = 200;

    @Min(10)
    @Max(100)
    private Integer titleMaxLength = 30;

    @Min(1)
    @Max(365)
    private Integer shortTermRetentionDays = 30;

    @Min(0)
    @Max(1)
    private Double shortTermDecayFactor = 0.03D;

    private Boolean longTermEnabled = true;

    private String longTermEmbeddingModel;

    @Min(0)
    @Max(1)
    private Double longTermImportanceThreshold = 0.6D;

    @Min(1)
    @Max(3650)
    private Integer longTermDormantDays = 30;

    @Min(0)
    @Max(1)
    private Double longTermDecayStep = 0.05D;

    @Min(0)
    @Max(1)
    private Double longTermMinImportance = 0.1D;

    private Boolean semanticEnabled = true;

    private Boolean qualityAssessmentEnabled = true;

    private String cleanupCron = "0 0/30 * * * ?";

    @Min(5)
    @Max(10080)
    private Integer idleConsolidationMinutes = 30;

    @Min(1)
    @Max(500)
    private Integer idleConsolidationBatchSize = 50;

    @Min(100)
    @Max(20000)
    private Integer maxMemoryTokenBudget = 1800;

    @Min(0)
    @Max(1)
    private Double workingMemoryTokenRatio = 0.4D;

    @Min(0)
    @Max(1)
    private Double shortTermTokenRatio = 0.3D;

    @Min(0)
    @Max(1)
    private Double longTermTokenRatio = 0.2D;

    @Min(0)
    @Max(1)
    private Double semanticTokenRatio = 0.1D;

    @Min(1)
    @Max(100)
    private Integer shortTermTopK = 5;

    @Min(1)
    @Max(50)
    private Integer longTermTopK = 3;

    @Min(1)
    @Max(50)
    private Integer semanticTopK = 2;

    @Min(0)
    @Max(1)
    private Double cleanupDecayThreshold = 0.15D;
}

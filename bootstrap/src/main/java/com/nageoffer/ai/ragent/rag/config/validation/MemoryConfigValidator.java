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

package com.nageoffer.ai.ragent.rag.config.validation;

import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * 记忆配置校验器。
 */
public class MemoryConfigValidator implements ConstraintValidator<ValidMemoryConfig, MemoryProperties> {

    @Override
    public boolean isValid(MemoryProperties config, ConstraintValidatorContext context) {
        if (config == null) {
            return true;
        }
        if (Boolean.TRUE.equals(config.getSummaryEnabled())) {
            Integer summaryStartTurns = config.getSummaryStartTurns();
            Integer historyKeepTurns = config.getHistoryKeepTurns();
            if (summaryStartTurns != null
                    && historyKeepTurns != null
                    && summaryStartTurns <= historyKeepTurns) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                        "当启用摘要时，summaryStartTurns 必须大于 historyKeepTurns"
                ).addConstraintViolation();
                return false;
            }
        }
        double ratioSum = defaultDouble(config.getWorkingMemoryTokenRatio())
                + defaultDouble(config.getShortTermTokenRatio())
                + defaultDouble(config.getLongTermTokenRatio())
                + defaultDouble(config.getSemanticTokenRatio());
        if (Math.abs(ratioSum - 1.0D) > 0.0001D) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    "记忆 Token 配额占比之和必须等于 1"
            ).addConstraintViolation();
            return false;
        }
        return true;
    }

    private double defaultDouble(Double value) {
        return value == null ? 0D : value;
    }
}

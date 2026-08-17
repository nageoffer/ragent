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
 * 记忆配置校验器
 * 校验摘要相关配置的合理性
 */
public class MemoryConfigValidator implements ConstraintValidator<ValidMemoryConfig, MemoryProperties> {

    @Override
    public boolean isValid(MemoryProperties config, ConstraintValidatorContext context) {
        if (config == null) {
            return true;
        }

        // 如果启用了摘要功能，需要校验配置的合理性
        if (Boolean.TRUE.equals(config.getSummaryEnabled())) {
            Integer summaryStartTurns = config.getSummaryStartTurns();
            Integer historyKeepTurns = config.getHistoryKeepTurns();

            // 摘要触发轮数必须大于保留轮数
            if (summaryStartTurns <= historyKeepTurns) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                        String.format(
                                "当启用摘要功能时，summaryStartTurns (%d) 必须大于 historyKeepTurns (%d)，" +
                                        "否则永远不会触发摘要。建议配置至少：summaryStartTurns = historyKeepTurns + 1",
                                summaryStartTurns, historyKeepTurns
                        )
                ).addConstraintViolation();
                return false;
            }
        }

        return true;
    }
}

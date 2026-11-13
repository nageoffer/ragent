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

import org.jspecify.annotations.NonNull;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

import java.util.List;

/**
 * Jev 配置缺口的启动诊断器
 */
public class JevConfigFailureAnalyzer extends AbstractFailureAnalyzer<JevConfigException> {

    @Override
    protected FailureAnalysis analyze(@NonNull Throwable rootFailure, JevConfigException cause) {
        List<JevConfigValidator.Violation> violations = cause.getViolations();

        StringBuilder description = new StringBuilder();
        description.append("意图识别已选用 Jev，但它的配置不完整（").append(violations.size()).append(" 项）：");
        StringBuilder action = new StringBuilder();
        action.append("补齐下列配置，或把 rag.intent.type 改回 llm：");

        int index = 1;
        for (JevConfigValidator.Violation v : violations) {
            description.append("\n  ").append(index).append(". ").append(v.problem());
            action.append("\n  ").append(index).append(". ").append(v.action());
            index++;
        }
        return new FailureAnalysis(description.toString(), action.toString(), cause);
    }
}

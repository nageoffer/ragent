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

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

import java.util.List;

/**
 * 检索通道配置矛盾的启动诊断器
 * <p>
 * 把 {@link RetrievalConfigException} 渲染成 Spring Boot 的 APPLICATION FAILED TO START 诊断框
 * （Description 罗列矛盾、Action 给出二选一修法），与端口占用的 PortInUseFailureAnalyzer 同款观感
 * <p>
 * 通过 {@code META-INF/spring.factories} 的 {@code org.springframework.boot.diagnostics.FailureAnalyzer} 注册
 */
public class RetrievalConfigFailureAnalyzer extends AbstractFailureAnalyzer<RetrievalConfigException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, RetrievalConfigException cause) {
        List<RetrievalChannelConfigValidator.Violation> violations = cause.getViolations();

        StringBuilder description = new StringBuilder();
        description.append("检索通道配置存在矛盾（").append(violations.size()).append(" 项）：");
        StringBuilder action = new StringBuilder();
        action.append("按需二选一修正：");

        int index = 1;
        for (RetrievalChannelConfigValidator.Violation v : violations) {
            String actual = v.actualType().isBlank() ? "<未设置>" : v.actualType();
            description.append("\n  ").append(index).append(". ")
                    .append(v.enabledKey()).append("=true，但").append(v.channelLabel())
                    .append("后端未启用（").append(v.typeKey()).append("=").append(actual)
                    .append("，需为 ").append(v.requiredType()).append("）")
                    .append("\n     → 该通道不会被注册，启用标志形同虚设");
            action.append("\n  ").append(v.channelLabel()).append("：")
                    .append("\n    • 启用该检索：设 ").append(v.typeKey()).append("=").append(v.requiredType())
                    .append(" ").append(v.enableHint())
                    .append("\n    • 关闭该通道：设 ").append(v.enabledKey()).append("=false");
            index++;
        }

        return new FailureAnalysis(description.toString(), action.toString(), cause);
    }
}

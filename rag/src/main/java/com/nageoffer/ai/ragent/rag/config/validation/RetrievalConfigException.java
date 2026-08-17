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

import lombok.Getter;

import java.util.List;

/**
 * 检索通道配置矛盾异常
 * <p>
 * 由 {@link RetrievalConfigEnvironmentPostProcessor} 在环境就绪阶段抛出，中断启动；
 * 携带全部违规交由 {@link RetrievalConfigFailureAnalyzer} 渲染成 APPLICATION FAILED TO START 诊断框
 * <p>
 * 有意为纯 {@link RuntimeException} 而非项目内 ServiceException：此为启动期配置错误，
 * 早于 Web 容器与全局异常处理器，不该走业务异常那套错误码 / HTTP 映射
 */
@Getter
public class RetrievalConfigException extends RuntimeException {

    private final transient List<RetrievalChannelConfigValidator.Violation> violations;

    public RetrievalConfigException(List<RetrievalChannelConfigValidator.Violation> violations) {
        super("检索通道配置存在矛盾（" + violations.size() + " 项）：后端未装配却启用了对应检索通道");
        this.violations = violations;
    }
}

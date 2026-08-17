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

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.List;

/**
 * 检索通道配置一致性校验（启动最前置的核心 Hook）
 * <p>
 * {@link EnvironmentPostProcessor} 在 {@code prepareEnvironment()} 阶段执行，早于任何 bean、Web 容器与 Banner，
 * 直接读整份 {@link ConfigurableEnvironment}。发现「后端未装配却开了检索通道」即抛 {@link RetrievalConfigException}
 * 中断启动，由 {@link RetrievalConfigFailureAnalyzer} 渲染成端口占用同款的诊断框
 * <p>
 * 通过 {@code META-INF/spring.factories} 注册
 * <p>
 * 排到 {@link Ordered#LOWEST_PRECEDENCE}：必须晚于加载 application.yaml 的 ConfigDataEnvironmentPostProcessor，
 * 否则读不到配置项而漏判
 */
public class RetrievalConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<RetrievalChannelConfigValidator.Violation> violations = RetrievalChannelConfigValidator.validate(
                environment::getProperty,
                key -> environment.getProperty(key, Boolean.class, false));
        if (!violations.isEmpty()) {
            throw new RetrievalConfigException(violations);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

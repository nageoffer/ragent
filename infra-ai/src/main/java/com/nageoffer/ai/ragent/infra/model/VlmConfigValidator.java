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

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * VLM 配置启动期校验器
 */
@Component
@RequiredArgsConstructor
public class VlmConfigValidator implements InitializingBean {

    private final AIModelProperties properties;

    @Override
    public void afterPropertiesSet() {
        AIModelProperties.ModelGroup group = properties.getVlm();
        if (group == null || group.getCandidates() == null || group.getCandidates().isEmpty()) {
            return;
        }

        Long timeoutMs = group.getTimeoutMs();
        if (timeoutMs != null && timeoutMs <= 0) {
            throw new IllegalStateException("VLM 配置校验失败: ai.vlm.timeout-ms 必须为正数");
        }
    }
}

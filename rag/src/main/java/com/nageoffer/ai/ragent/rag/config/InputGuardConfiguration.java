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

import cn.hutool.core.util.StrUtil;
import com.aliyun.green20220302.Client;
import com.aliyun.teaopenapi.models.Config;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.jev.JevClient;
import com.nageoffer.ai.ragent.rag.core.guard.AliyunInputRiskDetector;
import com.nageoffer.ai.ragent.rag.core.guard.InputRiskDetector;
import com.nageoffer.ai.ragent.rag.core.guard.JevInputRiskDetector;
import com.nageoffer.ai.ragent.rag.core.guard.LlmInputRiskDetector;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * 按 {@link InputGuardProperties#getType()} 装配唯一的输入审核实现
 * <p>
 * 只在 Agent 引擎下装配：workflow 部署不要求配置审核凭据
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "ragent.engine", name = "type", havingValue = "agent")
public class InputGuardConfiguration {

    private static final int ALIYUN_CONNECT_TIMEOUT_MS = 3000;
    private static final int ALIYUN_READ_TIMEOUT_MS = 6000;

    @Bean
    public InputRiskDetector inputRiskDetector(InputGuardProperties properties,
                                               LLMService llmService,
                                               PromptTemplateLoader promptTemplateLoader,
                                               JevClient jevClient) {
        log.info("用户提问审核方式：{}", properties.getType().getDisplayName());
        return switch (properties.getType()) {
            case LLM -> new LlmInputRiskDetector(llmService, promptTemplateLoader);
            case ALIYUN -> new AliyunInputRiskDetector(aliyunClient(properties.getAliyun()));
            case JEV -> new JevInputRiskDetector(jevClient, promptTemplateLoader);
            case NONE -> {
                log.warn("用户提问审核未启用，所有提问直接放行");
                yield (question, previousTurn) -> Set.of();
            }
        };
    }

    private static Client aliyunClient(InputGuardProperties.Aliyun aliyun) {
        if (StrUtil.hasBlank(aliyun.getAccessKeyId(), aliyun.getAccessKeySecret())) {
            throw new IllegalStateException(
                    "ragent.input-guard.type=aliyun 需要配置 ragent.input-guard.aliyun.access-key-id / access-key-secret");
        }
        Config config = new Config()
                .setAccessKeyId(aliyun.getAccessKeyId())
                .setAccessKeySecret(aliyun.getAccessKeySecret())
                .setEndpoint(aliyun.getEndpoint())
                .setRegionId(aliyun.getRegionId())
                .setConnectTimeout(ALIYUN_CONNECT_TIMEOUT_MS)
                .setReadTimeout(ALIYUN_READ_TIMEOUT_MS);
        try {
            return new Client(config);
        } catch (Exception e) {
            throw new IllegalStateException("阿里云审核客户端创建失败", e);
        }
    }
}

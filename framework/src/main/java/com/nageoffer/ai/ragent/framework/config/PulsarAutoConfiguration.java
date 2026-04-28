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

package com.nageoffer.ai.ragent.framework.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.mq.config.PulsarProperties;
import com.nageoffer.ai.ragent.framework.mq.outbox.OutboxEventPublisher;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.framework.mq.producer.PulsarProducerAdapter;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.AuthenticationFactory;
import org.apache.pulsar.client.api.PulsarClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * Pulsar 自动装配。
 */
@Configuration
@EnableConfigurationProperties(PulsarProperties.class)
@ConditionalOnProperty(prefix = "messaging.pulsar", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PulsarAutoConfiguration {

    @Bean(destroyMethod = "close")
    public PulsarClient pulsarClient(PulsarProperties pulsarProperties) throws Exception {
        var clientBuilder = PulsarClient.builder()
                .serviceUrl(pulsarProperties.getServiceUrl())
                .ioThreads(pulsarProperties.getIoThreads())
                .listenerThreads(pulsarProperties.getListenerThreads())
                .operationTimeout(pulsarProperties.getOperationTimeoutMs(), TimeUnit.MILLISECONDS);

        // 配置认证信息（如果提供了认证参数）
        if (StringUtils.hasText(pulsarProperties.getAuthPluginClassName()) 
                && StringUtils.hasText(pulsarProperties.getAuthParams())) {
            Authentication authentication = AuthenticationFactory.create(
                    pulsarProperties.getAuthPluginClassName(), 
                    pulsarProperties.getAuthParams());
            clientBuilder.authentication(authentication);
        }

        return clientBuilder.build();
    }

    @Bean(destroyMethod = "close")
    public PulsarAdmin pulsarAdmin(PulsarProperties pulsarProperties) throws Exception {
        var adminBuilder = PulsarAdmin.builder()
                .serviceHttpUrl(pulsarProperties.getAdminUrl());

        // 配置认证信息（如果提供了认证参数）
        if (StringUtils.hasText(pulsarProperties.getAuthPluginClassName()) 
                && StringUtils.hasText(pulsarProperties.getAuthParams())) {
            Authentication authentication = AuthenticationFactory.create(
                    pulsarProperties.getAuthPluginClassName(), 
                    pulsarProperties.getAuthParams());
            adminBuilder.authentication(authentication);
        }

        return adminBuilder.build();
    }

    @Bean
    public MessageQueueProducer messageQueueProducer(PulsarClient pulsarClient,
                                                     ObjectMapper objectMapper,
                                                     OutboxEventPublisher outboxEventPublisher,
                                                     PulsarProperties pulsarProperties) {
        return new PulsarProducerAdapter(pulsarClient, objectMapper, outboxEventPublisher, pulsarProperties);
    }
}

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

package com.nageoffer.ai.ragent.framework.mq.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pulsar 配置属性。
 */
@Data
@ConfigurationProperties(prefix = "messaging.pulsar")
public class PulsarProperties {

    private boolean enabled = true;

    private String serviceUrl = "pulsar://127.0.0.1:6650";

    private String adminUrl = "http://127.0.0.1:8080";

    private String tenant = "ragent";

    private String namespace = "ai";

    private String authPluginClassName;

    private String authParams;

    private int ioThreads = 4;

    private int listenerThreads = 4;

    private int operationTimeoutMs = 30000;

    private Producer producer = new Producer();

    private Consumer consumer = new Consumer();

    private Topics topics = new Topics();

    @Data
    public static class Producer {

        private int sendTimeoutMs = 30000;

        private boolean blockIfQueueFull = true;

        private boolean batchingEnabled = true;

        private int batchingMaxMessages = 200;

        private int batchingMaxPublishDelayMs = 5;

        private String compressionType = "LZ4";
    }

    @Data
    public static class Consumer {

        private int ackTimeoutSeconds = 60;

        private int negativeAckDelaySeconds = 30;

        private int receiverQueueSize = 500;

        private int maxRedeliverCount = 8;
    }

    @Data
    public static class Topics {

        private String knowledgeDocumentChunk = "persistent://ragent/ai/knowledge-document-chunk";

        private String messageFeedback = "persistent://ragent/ai/message-feedback";
    }
}

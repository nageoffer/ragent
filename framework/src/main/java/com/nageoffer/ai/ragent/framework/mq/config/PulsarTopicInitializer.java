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

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Pulsar 资源初始化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(PulsarAdmin.class)
public class PulsarTopicInitializer {

    private final PulsarAdmin pulsarAdmin;
    private final PulsarProperties pulsarProperties;

    @PostConstruct
    public void init() {
        try {
            ensureTenant();
            ensureNamespace();
            ensurePartitionedTopic(pulsarProperties.getTopics().getKnowledgeDocumentChunk(), 4);
            ensurePartitionedTopic(pulsarProperties.getTopics().getMessageFeedback(), 2);
        } catch (Exception ex) {
            log.error("Pulsar topic initializer failed", ex);
        }
    }

    private void ensureTenant() throws PulsarAdminException {
        String tenant = pulsarProperties.getTenant();
        if (pulsarAdmin.tenants().getTenants().contains(tenant)) {
            return;
        }
        pulsarAdmin.tenants().createTenant(tenant,
                TenantInfo.builder()
                        .allowedClusters(Set.of("standalone"))
                        .adminRoles(Set.of())
                        .build());
    }

    private void ensureNamespace() throws PulsarAdminException {
        String namespace = pulsarProperties.getTenant() + "/" + pulsarProperties.getNamespace();
        if (pulsarAdmin.namespaces().getNamespaces(pulsarProperties.getTenant()).contains(namespace)) {
            return;
        }
        pulsarAdmin.namespaces().createNamespace(namespace);
    }

    private void ensurePartitionedTopic(String topic, int partitions) throws PulsarAdminException {
        try {
            pulsarAdmin.topics().getPartitionedTopicMetadata(topic);
            if (pulsarAdmin.topics().getPartitionedTopicMetadata(topic).partitions == 0) {
                pulsarAdmin.topics().createPartitionedTopic(topic, partitions);
            }
        } catch (PulsarAdminException.NotFoundException ex) {
            pulsarAdmin.topics().createPartitionedTopic(topic, partitions);
        } catch (PulsarAdminException.ConflictException ignored) {
            log.debug("Pulsar topic already exists: {}", topic);
        }
    }
}

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

package com.nageoffer.ai.ragent.knowledge.mq;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeBaseCleanupEvent;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import com.nageoffer.ai.ragent.rag.core.keyword.KeywordIndexService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 知识库删除清理 MQ 消费者
 * 负责异步回收知识库独占的底层物理资源：向量数据、bucket、ES 关键词索引、知识图谱数据
 * <p>
 * 各清理项 best-effort 互不影响，存在失败项则抛异常触发重试；所有操作均幂等，重试安全
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "knowledge-base-cleanup_topic${unique-name:}",
        consumerGroup = "knowledge-base-cleanup_cg${unique-name:}"
)
public class KnowledgeBaseCleanupConsumer implements RocketMQListener<MessageWrapper<KnowledgeBaseCleanupEvent>> {

    private final VectorStoreAdmin vectorStoreAdmin;
    private final FileStorageService fileStorageService;
    /**
     * 关键词索引实现惰性解析：rag.keyword.type=none 时无该 bean，getIfAvailable() 返回 null 即跳过 ES 清理
     */
    private final ObjectProvider<KeywordIndexService> keywordIndexServiceProvider;
    /**
     * 图谱客户端惰性解析：rag.graph.type=none 时无该 bean，getIfAvailable() 返回 null 即跳过图谱清理
     */
    private final ObjectProvider<LightRagClient> lightRagClientProvider;

    @Override
    public void onMessage(MessageWrapper<KnowledgeBaseCleanupEvent> message) {
        KnowledgeBaseCleanupEvent event = message.getBody();
        String collectionName = event.getCollectionName();

        log.info("[消费者] 开始清理知识库物理资源，kbId={}, collectionName={}", event.getKbId(), collectionName);

        boolean allSucceeded = true;

        try {
            vectorStoreAdmin.dropVectorSpace(collectionName);
        } catch (Exception e) {
            allSucceeded = false;
            log.error("清理向量空间失败，collectionName={}", collectionName, e);
        }

        try {
            fileStorageService.deleteKnowledgeSpace(collectionName);
        } catch (Exception e) {
            allSucceeded = false;
            log.error("删除知识库存储目录失败，namespace={}", collectionName, e);
        }

        KeywordIndexService keywordIndexService = keywordIndexServiceProvider.getIfAvailable();
        if (keywordIndexService != null) {
            try {
                keywordIndexService.deleteByCollection(collectionName);
            } catch (Exception e) {
                allSucceeded = false;
                log.error("删除 ES 关键词索引失败，collectionName={}", collectionName, e);
            }
        }

        LightRagClient lightRagClient = lightRagClientProvider.getIfAvailable();
        if (lightRagClient != null) {
            try {
                lightRagClient.deleteByCollection(collectionName);
            } catch (Exception e) {
                allSucceeded = false;
                log.error("删除 LightRAG 图谱数据失败，collectionName={}", collectionName, e);
            }
        }

        if (!allSucceeded) {
            throw new ServiceException("知识库物理资源清理存在失败项，触发重试");
        }
    }
}

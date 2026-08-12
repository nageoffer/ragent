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
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentCleanupEvent;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import com.nageoffer.ai.ragent.rag.core.keyword.KeywordIndexService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 文档删除清理消费者
 * <p>
 * 每个外部资源独立尝试，全部尝试结束后只要存在真实失败就抛错触发 MQ 重试。所有删除操作均须幂等
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "knowledge-document-cleanup_topic${unique-name:}",
        consumerGroup = "knowledge-document-cleanup_cg${unique-name:}"
)
public class KnowledgeDocumentCleanupConsumer
        implements RocketMQListener<MessageWrapper<KnowledgeDocumentCleanupEvent>> {

    private final VectorStoreService vectorStoreService;
    private final FileStorageService fileStorageService;
    private final ObjectProvider<KeywordIndexService> keywordIndexServiceProvider;
    private final ObjectProvider<LightRagClient> lightRagClientProvider;

    @Override
    public void onMessage(MessageWrapper<KnowledgeDocumentCleanupEvent> message) {
        KnowledgeDocumentCleanupEvent event = message.getBody();
        String docId = event.getDocId();
        String collectionName = event.getCollectionName();

        log.info("[消费者] 开始清理文档外部资源，docId={}, collectionName={}", docId, collectionName);
        boolean allSucceeded = true;

        try {
            // 此阶段仅会让外部向量数据库 Milvus 执行清理(若使用)
            vectorStoreService.deleteDocumentVectorsAfterCommit(collectionName, docId);
        } catch (Exception e) {
            allSucceeded = false;
            log.error("删除文档主向量失败，collectionName={}, docId={}", collectionName, docId, e);
        }

        KeywordIndexService keywordIndexService = keywordIndexServiceProvider.getIfAvailable();
        if (keywordIndexService != null) {
            try {
                keywordIndexService.deleteDocumentIndex(collectionName, docId);
            } catch (Exception e) {
                allSucceeded = false;
                log.error("删除文档 ES 索引失败，collectionName={}, docId={}", collectionName, docId, e);
            }
        }

        LightRagClient lightRagClient = lightRagClientProvider.getIfAvailable();
        if (lightRagClient != null) {
            try {
                lightRagClient.deleteByDocOrThrow(docId);
            } catch (Exception e) {
                allSucceeded = false;
                log.error("删除文档 LightRAG 数据失败，docId={}", docId, e);
            }
        }

        if (StringUtils.hasText(event.getFileUrl())) {
            try {
                fileStorageService.deleteByUrl(event.getFileUrl());
            } catch (Exception e) {
                allSucceeded = false;
                log.error("删除文档对象文件失败，docId={}, fileUrl={}", docId, event.getFileUrl(), e);
            }
        }

        if (!allSucceeded) {
            throw new ServiceException("文档外部资源清理存在失败项，触发重试");
        }
    }
}

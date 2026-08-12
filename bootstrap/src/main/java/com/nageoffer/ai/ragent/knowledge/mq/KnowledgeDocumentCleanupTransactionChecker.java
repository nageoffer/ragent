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

import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener;
import com.nageoffer.ai.ragent.framework.mq.producer.TransactionChecker;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentCleanupEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 文档删除清理事务消息回查器
 * <p>
 * 只以文档是否已逻辑删除作为本地事务提交凭据
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentCleanupTransactionChecker implements TransactionChecker {

    private final KnowledgeDocumentMapper documentMapper;
    private final DelegatingTransactionListener transactionListener;

    @Value("knowledge-document-cleanup_topic${unique-name:}")
    private String cleanupTopic;

    @PostConstruct
    public void init() {
        transactionListener.registerChecker(cleanupTopic, this);
    }

    @Override
    public boolean check(MessageWrapper<?> message) {
        log.info("[事务回查] 文档删除清理，消息体：{}", JSONUtil.toJsonStr(message));

        KnowledgeDocumentCleanupEvent event = (KnowledgeDocumentCleanupEvent) message.getBody();
        // @TableLogic 会让已删除行对 selectById 不可见,删除事务消息只会在确认文档存在后发送
        KnowledgeDocumentDO document = documentMapper.selectById(event.getDocId());
        return document == null || Integer.valueOf(1).equals(document.getDeleted());
    }
}

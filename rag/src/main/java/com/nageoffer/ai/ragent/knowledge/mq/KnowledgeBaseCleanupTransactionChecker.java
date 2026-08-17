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
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeBaseCleanupEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 知识库删除清理事务消息回查器
 * <p>
 * 按 topic 注册，Broker 回查时可路由到任意实例，通过查询 DB 中知识库是否已逻辑删除判断本地事务是否已提交
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseCleanupTransactionChecker implements TransactionChecker {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DelegatingTransactionListener transactionListener;

    @Value("knowledge-base-cleanup_topic${unique-name:}")
    private String cleanupTopic;

    @PostConstruct
    public void init() {
        transactionListener.registerChecker(cleanupTopic, this);
    }

    @Override
    public boolean check(MessageWrapper<?> message) {
        log.info("[事务回查] 知识库删除清理，消息体：{}", JSONUtil.toJsonStr(message));

        KnowledgeBaseCleanupEvent event = (KnowledgeBaseCleanupEvent) message.getBody();
        // 逻辑删除后 selectById 不可见；查不到即视为本地事务（软删知识库）已提交
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(event.getKbId());
        return kbDO == null;
    }
}

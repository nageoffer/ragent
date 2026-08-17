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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.core.source.CitationMarkup;
import com.nageoffer.ai.ragent.rag.dto.RecommendedQuestionsPayload;
import com.nageoffer.ai.ragent.rag.service.RecommendedQuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 推荐追问问题服务默认实现
 * <p>
 * null 表示未生成，空数组表示已生成但无合适追问，非空数组表示生成成功
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendedQuestionServiceImpl implements RecommendedQuestionService {

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_USER = "user";

    private final ConversationMessageMapper conversationMessageMapper;
    private final RecommendedQuestionGenerator generator;

    @Override
    public RecommendedQuestionsPayload generate(String messageId, String userId) {
        ConversationMessageDO message = loadAssistantMessage(messageId, userId);
        if (isRecommendationDisabled(message)) {
            return RecommendedQuestionsPayload.empty();
        }

        List<String> cached = message.getRecommendedQuestions();
        if (cached != null) {
            return RecommendedQuestionsPayload.success(cached);
        }

        String question = loadQuestion(message);
        RecommendedQuestionsPayload generated =
                generator.generate(question, CitationMarkup.strip(message.getContent()), message.getRetrievedChunks());
        if (generated.status() == RecommendedQuestionsPayload.Status.FAILED) {
            return generated;
        }

        // SUCCESS 与 EMPTY 都落库，空数组作为有效的负缓存
        ConversationMessageDO update = new ConversationMessageDO();
        update.setId(message.getId());
        update.setRecommendedQuestions(generated.questions());
        conversationMessageMapper.updateById(update);
        return generated;
    }

    /**
     * 定位 assistant 消息并校验归属（他人消息或非 assistant 消息一律视为不存在）
     */
    private ConversationMessageDO loadAssistantMessage(String messageId, String userId) {
        ConversationMessageDO message = conversationMessageMapper.selectOne(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getId, messageId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getRole, ROLE_ASSISTANT)
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
        if (message == null) {
            throw new ClientException("消息不存在");
        }
        return message;
    }

    /**
     * 通过明确的 replyToMessageId 获取当前答案对应的用户提问
     */
    private String loadQuestion(ConversationMessageDO message) {
        if (StrUtil.isBlank(message.getReplyToMessageId())) {
            return null;
        }
        ConversationMessageDO question = conversationMessageMapper.selectOne(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getId, message.getReplyToMessageId())
                        .eq(ConversationMessageDO::getConversationId, message.getConversationId())
                        .eq(ConversationMessageDO::getUserId, message.getUserId())
                        .eq(ConversationMessageDO::getRole, ROLE_USER)
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
        return question == null ? null : question.getContent();
    }

    private boolean isRecommendationDisabled(ConversationMessageDO message) {
        return StrUtil.isNotBlank(message.getMessageStatus())
                && !ChatMessage.MessageStatus.NORMAL.name().equals(message.getMessageStatus());
    }
}

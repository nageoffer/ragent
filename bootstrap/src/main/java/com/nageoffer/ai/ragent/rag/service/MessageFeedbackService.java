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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.controller.request.MessageFeedbackRequest;
import com.nageoffer.ai.ragent.rag.mq.event.MessageFeedbackEvent;

import java.util.List;
import java.util.Map;

public interface MessageFeedbackService {

    /**
     * 提交会话消息反馈（同步，供内部直接调用）
     *
     * @param messageId 消息ID
     * @param request   反馈内容
     */
    void submitFeedback(String messageId, MessageFeedbackRequest request);

    /**
     * 提交会话消息反馈（异步，通过 MQ 持久化）
     *
     * @param messageId 消息ID
     * @param request   反馈内容
     */
    void submitFeedbackAsync(String messageId, MessageFeedbackRequest request);

    /**
     * 取消会话消息反馈（异步，通过 MQ 持久化）
     *
     * @param messageId 消息ID
     */
    void cancelFeedbackAsync(String messageId);

    /**
     * 通过 MQ 事件异步持久化反馈（由消费者调用）
     *
     * @param event 反馈事件
     */
    void submitFeedbackByEvent(MessageFeedbackEvent event);

    /**
     * 查询用户在一批消息上的反馈值
     *
     * @param userId     用户ID
     * @param messageIds 消息ID列表
     * @return messageId -> vote
     */
    Map<String, Integer> getUserVotes(String userId, List<String> messageIds);
}

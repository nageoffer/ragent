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

import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationMessageBO;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationSummaryBO;

import java.util.List;

public interface ConversationMessageService {

    /**
     * 新增对话消息
     *
     * @param conversationMessage 消息内容
     */
    Long addMessage(ConversationMessageBO conversationMessage);

    /**
     * 获取对话消息列表（支持排序与数量限制）
     *
     * @param conversationId 对话ID
     * @param limit          限制数量
     * @param order          排序方式
     * @return 对话消息列表
     */
    List<ConversationMessageVO> listMessages(String conversationId, Integer limit, ConversationMessageOrder order);

    /**
     * 添加对话摘要
     *
     * @param conversationSummary 对话摘要内容
     */
    void addMessageSummary(ConversationSummaryBO conversationSummary);
}

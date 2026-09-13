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

package com.nageoffer.ai.ragent.agent.service;

import com.nageoffer.ai.ragent.agent.controller.vo.AgentConversationVO;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentMessageVO;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.enums.AgentMessageStatus;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;

import java.util.List;

/**
 * Agent 会话最小 CRUD：建 / 列 / 历史 / 删，展示层双写与 AgentScope 状态存储互不感知
 */
public interface AgentConversationService {

    /**
     * 首问建会话（截断问题作标题），已存在则刷新最后活动时间，返回会话标题
     */
    String touchConversation(String conversationId, String userId, String question);

    /**
     * 数据库写入用户提问消息，返回这条消息的 messageId，后续 Agent 回复要关联这条消息。
     */
    String addUserMessage(String conversationId, String userId, String content);

    String addAssistantMessage(String conversationId, String userId, String content, String thinkingContent,
                               List<AgentBlock> blocks, String replyToMessageId, AgentMessageStatus status);

    List<AgentConversationVO> listByUserId(String userId);

    List<AgentMessageVO> listMessages(String conversationId, String userId);

    /**
     * 取最近若干轮已配对的 user/assistant 正文，按时间正序
     * 供知识检索工具做改写指代消解，不含运行轨迹与深度思考内容
     */
    List<ChatMessage> loadRecentTurns(String conversationId, String userId, int turns);

    /**
     * 手动改标题，空白标题拒绝
     */
    void rename(String conversationId, String userId, String title);

    /**
     * 逻辑删会话与消息，同时清掉该会话的 Agent 工作状态
     */
    void delete(String conversationId, String userId);

    /**
     * 批量逻辑删，逐条走单删以保证状态清理不漏
     */
    void deleteBatch(List<String> conversationIds, String userId);
}

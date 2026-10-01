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
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmSettlement;
import com.nageoffer.ai.ragent.agent.enums.AgentMessageStatus;

import java.util.List;

/**
 * Agent 会话管理：建会话、消息读写、确认卡片结算、删除
 */
public interface AgentConversationService {

    /**
     * 首问使用服务端生成的新 ID 建会话，已有会话刷新最后活动时间，返回标题
     */
    String touchConversation(String conversationId, String userId, String question);

    /**
     * 保存用户消息
     */
    String addUserMessage(String conversationId, String userId, String content);

    /**
     * 保存助手消息
     */
    String addAssistantMessage(String conversationId, String userId, String content, String thinkingContent,
                               List<AgentBlock> blocks, String replyToMessageId, AgentMessageStatus status,
                               Long durationMs);

    /**
     * 结算挂起的确认卡片：卡片状态改 approved/denied，消息状态改回 NORMAL
     */
    AgentConfirmSettlement settlePendingConfirm(String conversationId, String userId, String messageId, boolean approved);

    /**
     * 续跑前的检查：卡片还挂在待确认状态才放行，这里只读不落库
     * 把卡片改成终态是 settlePendingConfirm 的事，要等流真的启动那一刻才做
     */
    AgentConfirmSettlement getPendingConfirm(String conversationId, String userId, String messageId);

    /**
     * Agent 状态里已无待确认工具，但卡片还是 pending，标记为 expired 以解除会话阻塞
     */
    void expirePendingConfirm(String conversationId, String userId, String messageId);

    /**
     * 校验当前用户的会话存在，并查询是否有待确认的操作；不存在或已删除时抛出异常
     */
    boolean hasPendingConfirm(String conversationId, String userId);

    /**
     * 查询用户的会话列表
     */
    List<AgentConversationVO> listByUserId(String userId);

    /**
     * 查询会话消息列表
     */
    List<AgentMessageVO> listMessages(String conversationId, String userId);

    /**
     * 手动改标题，空白标题拒绝
     */
    void rename(String conversationId, String userId, String title);

    /**
     * 删除会话、消息及对应的 Agent 状态
     */
    void delete(String conversationId, String userId);

    /**
     * 批量删除，逐条走 delete 保证状态清理不漏
     */
    void deleteBatch(List<String> conversationIds, String userId);
}

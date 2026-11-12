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

package com.nageoffer.ai.ragent.agent.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentConversationVO;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentMessageVO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentConversationDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentConversationMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmCall;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmSettlement;
import com.nageoffer.ai.ragent.agent.enums.AgentMessageStatus;
import com.nageoffer.ai.ragent.agent.enums.AgentToolStatus;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 会话管理实现
 */
@Slf4j
@Service
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentConversationServiceImpl implements AgentConversationService {

    private static final int TITLE_MAX_LENGTH = 30;
    private static final int RENAME_MAX_LENGTH = 128;
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String CONFIRM_STATUS_PENDING = "pending";
    private static final String CONFIRM_STATUS_APPROVED = "approved";
    private static final String CONFIRM_STATUS_DENIED = "denied";
    private static final String CONFIRM_STATUS_EXPIRED = "expired";

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final PgAgentStateStore agentStateStore;
    private final AgentRunGate runGate;

    @Override
    public String touchConversation(String conversationId, String userId, String question) {
        AgentConversationDO conversation = selectConversation(conversationId, userId);
        if (conversation == null) {
            return createConversation(conversationId, userId, question);
        }
        conversation.setLastTime(new Date());
        conversationMapper.updateById(conversation);
        return conversation.getTitle();
    }

    private String createConversation(String conversationId, String userId, String question) {
        // v1 简化：截断首问作标题，不走 LLM 生成
        String title = StrUtil.sub(StrUtil.emptyIfNull(question).trim(), 0, TITLE_MAX_LENGTH);
        AgentConversationDO conversation = AgentConversationDO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .title(title)
                .lastTime(new Date())
                .build();
        conversationMapper.insert(conversation);
        return title;
    }

    private AgentConversationDO selectConversation(String conversationId, String userId) {
        return conversationMapper.selectOne(Wrappers.lambdaQuery(AgentConversationDO.class)
                .eq(AgentConversationDO::getConversationId, conversationId)
                .eq(AgentConversationDO::getUserId, userId));
    }

    @Override
    public String addUserMessage(String conversationId, String userId, String content) {
        AgentMessageDO message = AgentMessageDO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(ROLE_USER)
                .content(content)
                .messageStatus(AgentMessageStatus.NORMAL.name())
                .build();
        messageMapper.insert(message);
        return message.getId();
    }

    @Override
    public String addAssistantMessage(String conversationId, String userId, String content, String thinkingContent,
                                      List<AgentBlock> blocks, String replyToMessageId, AgentMessageStatus status,
                                      Long durationMs) {
        AgentMessageDO message = AgentMessageDO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(ROLE_ASSISTANT)
                .content(content)
                .thinkingContent(StrUtil.blankToDefault(thinkingContent, null))
                .blocks(blocks)
                .replyToMessageId(replyToMessageId)
                .messageStatus(status.name())
                .durationMs(durationMs)
                .build();
        messageMapper.insert(message);
        return message.getId();
    }

    @Override
    public AgentConfirmSettlement getPendingConfirm(String conversationId, String userId, String messageId) {
        AgentConversationDO conversation = selectConversation(conversationId, userId);
        if (conversation == null) {
            throw new ClientException("会话不存在");
        }
        PendingConfirm pending = selectPendingConfirm(conversationId, userId, messageId);
        if (pending == null) {
            throw new ClientException("待确认的操作不存在或已处理");
        }
        return new AgentConfirmSettlement(conversation.getTitle(), pending.message().getReplyToMessageId());
    }

    @Override
    public AgentConfirmSettlement settlePendingConfirm(String conversationId, String userId,
                                                       String messageId, boolean approved) {
        AgentConversationDO conversation = selectConversation(conversationId, userId);
        if (conversation == null) {
            throw new ClientException("会话不存在");
        }
        AgentMessageDO message = settleConfirmBlock(conversationId, userId, messageId,
                approved ? CONFIRM_STATUS_APPROVED : CONFIRM_STATUS_DENIED);
        if (message == null) {
            throw new ClientException("待确认的操作不存在或已处理");
        }
        return new AgentConfirmSettlement(conversation.getTitle(), message.getReplyToMessageId());
    }

    @Override
    public void expirePendingConfirm(String conversationId, String userId, String messageId) {
        // 卡片标记失效，没找到说明已被结算过
        if (settleConfirmBlock(conversationId, userId, messageId, CONFIRM_STATUS_EXPIRED) != null) {
            log.warn("待确认卡片已失效，标记结算, conversationId: {}, messageId: {}", conversationId, messageId);
        }
    }

    /**
     * 把挂起的确认卡片改写成终态并落库，返回结算后的消息，没有可结算的卡片返回 null
     */
    private AgentMessageDO settleConfirmBlock(String conversationId, String userId,
                                              String messageId, String blockStatus) {
        PendingConfirm pending = selectPendingConfirm(conversationId, userId, messageId);
        if (pending == null) {
            return null;
        }
        pending.block().setStatus(blockStatus);
        // 卡片有了终态，消息改回 NORMAL 以解除新提问的阻塞
        AgentMessageDO message = pending.message();
        if (CONFIRM_STATUS_DENIED.equals(blockStatus) && CollUtil.isNotEmpty(pending.block().getCalls())) {
            Set<String> deniedCallIds = pending.block().getCalls().stream()
                    .map(AgentConfirmCall::getToolCallId)
                    .filter(StrUtil::isNotBlank)
                    .collect(Collectors.toSet());
            // 只结算卡片点名的待确认调用，同批其余工具可能只是随批暂停
            // 同意不是执行成功，失效也不是拒绝；两者均不改写工具状态
            message.getBlocks().stream()
                    .filter(block -> AgentBlock.KIND_TOOL.equals(block.getKind()))
                    .filter(block -> AgentToolStatus.AWAITING.value().equals(block.getStatus()))
                    .filter(block -> deniedCallIds.contains(block.getToolCallId()))
                    .forEach(block -> block.setStatus(AgentToolStatus.DENIED.value()));
        }
        message.setMessageStatus(AgentMessageStatus.NORMAL.name());
        messageMapper.updateById(message);
        return message;
    }

    /**
     * 查出仍挂着 pending 确认卡片的消息，连同卡片块一起返回，没有则返回 null
     */
    private PendingConfirm selectPendingConfirm(String conversationId, String userId, String messageId) {
        AgentMessageDO message = messageMapper.selectOne(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getId, messageId)
                .eq(AgentMessageDO::getConversationId, conversationId)
                .eq(AgentMessageDO::getUserId, userId));
        if (message == null || !AgentMessageStatus.AWAITING_CONFIRM.name().equals(message.getMessageStatus())) {
            return null;
        }
        AgentBlock block = findPendingConfirmBlock(message);
        return block == null ? null : new PendingConfirm(message, block);
    }

    /**
     * 一次查询查出的两样东西：待确认的那条消息，和它里面那张 pending 卡片
     */
    private record PendingConfirm(AgentMessageDO message, AgentBlock block) {
    }

    @Override
    public boolean hasPendingConfirm(String conversationId, String userId) {
        if (selectConversation(conversationId, userId) == null) {
            throw new ClientException("会话不存在");
        }
        return messageMapper.exists(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getConversationId, conversationId)
                .eq(AgentMessageDO::getUserId, userId)
                .eq(AgentMessageDO::getMessageStatus, AgentMessageStatus.AWAITING_CONFIRM.name()));
    }

    private static AgentBlock findPendingConfirmBlock(AgentMessageDO message) {
        if (CollUtil.isEmpty(message.getBlocks())) {
            return null;
        }
        return message.getBlocks().stream()
                .filter(block -> AgentBlock.KIND_CONFIRM.equals(block.getKind()))
                .filter(block -> CONFIRM_STATUS_PENDING.equals(block.getStatus()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<AgentConversationVO> listByUserId(String userId) {
        List<AgentConversationDO> conversations = conversationMapper.selectList(
                Wrappers.lambdaQuery(AgentConversationDO.class)
                        .eq(AgentConversationDO::getUserId, userId)
                        .orderByDesc(AgentConversationDO::getLastTime));
        Map<String, Long> turnCounts = countTurns(conversations, userId);
        return conversations.stream()
                .map(item -> AgentConversationVO.builder()
                        .conversationId(item.getConversationId())
                        .title(item.getTitle())
                        .lastTime(item.getLastTime())
                        .turns(turnCounts.getOrDefault(item.getConversationId(), 0L).intValue())
                        .build())
                .toList();
    }

    /**
     * 按会话统计用户提问数，一次 groupBy 避免 N+1
     */
    private Map<String, Long> countTurns(List<AgentConversationDO> conversations, String userId) {
        if (conversations.isEmpty()) {
            return Map.of();
        }
        List<String> ids = conversations.stream().map(AgentConversationDO::getConversationId).toList();
        QueryWrapper<AgentMessageDO> query = new QueryWrapper<AgentMessageDO>()
                .select("conversation_id", "COUNT(*) AS turn_count")
                .eq("user_id", userId)
                .eq("role", ROLE_USER)
                .in("conversation_id", ids)
                .groupBy("conversation_id");
        return messageMapper.selectMaps(query).stream()
                .collect(Collectors.toMap(
                        row -> String.valueOf(row.get("conversation_id")),
                        row -> ((Number) row.get("turn_count")).longValue()));
    }

    @Override
    public void rename(String conversationId, String userId, String title) {
        String trimmed = StrUtil.trimToEmpty(title);
        if (trimmed.isEmpty()) {
            throw new ClientException("会话标题不能为空");
        }
        AgentConversationDO conversation = conversationMapper.selectOne(
                Wrappers.lambdaQuery(AgentConversationDO.class)
                        .eq(AgentConversationDO::getConversationId, conversationId)
                        .eq(AgentConversationDO::getUserId, userId));
        if (conversation == null) {
            throw new ClientException("会话不存在");
        }
        conversation.setTitle(StrUtil.sub(trimmed, 0, RENAME_MAX_LENGTH));
        conversationMapper.updateById(conversation);
    }

    @Override
    public List<AgentMessageVO> listMessages(String conversationId, String userId) {
        return messageMapper.selectList(Wrappers.lambdaQuery(AgentMessageDO.class)
                        .eq(AgentMessageDO::getConversationId, conversationId)
                        .eq(AgentMessageDO::getUserId, userId)
                        .orderByAsc(AgentMessageDO::getId))
                .stream()
                .map(item -> AgentMessageVO.builder()
                        .id(item.getId())
                        .role(item.getRole())
                        .content(item.getContent())
                        .thinkingContent(item.getThinkingContent())
                        .blocks(item.getBlocks())
                        .messageStatus(item.getMessageStatus())
                        .durationMs(item.getDurationMs())
                        .createTime(item.getCreateTime())
                        .build())
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String conversationId, String userId) {
        Runnable releaseLock = runGate.acquireConversation(userId, conversationId);
        try {
            conversationMapper.delete(Wrappers.lambdaQuery(AgentConversationDO.class)
                    .eq(AgentConversationDO::getConversationId, conversationId)
                    .eq(AgentConversationDO::getUserId, userId));
            messageMapper.delete(Wrappers.lambdaQuery(AgentMessageDO.class)
                    .eq(AgentMessageDO::getConversationId, conversationId)
                    .eq(AgentMessageDO::getUserId, userId));
            // Agent 状态同库，随事务一起删；状态缓存由运行侧放锁前清空，这里不用管
            agentStateStore.delete(userId, conversationId);
        } finally {
            // 持锁到事务结束，批量删除等整批提交或回滚，期间新运行进不来
            afterCompletion(releaseLock);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBatch(List<String> conversationIds, String userId) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return;
        }
        // 自调用不经代理，各会话的删除逻辑并入当前事务
        conversationIds.stream().distinct().forEach(id -> delete(id, userId));
    }

    /**
     * 事务提交或回滚后执行，无事务时立即执行
     */
    private void afterCompletion(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                action.run();
            }
        });
    }
}

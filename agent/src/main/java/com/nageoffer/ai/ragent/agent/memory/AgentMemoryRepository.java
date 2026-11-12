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

package com.nageoffer.ai.ragent.agent.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryCursorDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryCursorMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.enums.AgentMemorySourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 长期记忆持久化层，主动整理与后台学习共用：读当前记忆与对话原文，以及唯一一份记忆写入逻辑
 * 后台消费游标的读写也在这里，但只有后台流程调用
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentMemoryRepository {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final AgentMemoryMapper memoryMapper;
    private final AgentMemoryCursorMapper cursorMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentMemoryProperties memoryProperties;

    /**
     * 注入用的记忆视图；开关关闭与读库异常一律回空快照，注入侧据此透传
     */
    public AgentMemorySnapshot loadSnapshot(String userId) {
        if (!memoryProperties.isLongTermEnabled()) {
            return AgentMemorySnapshot.empty();
        }
        try {
            return new AgentMemorySnapshot(listActiveItems(userId));
        } catch (Exception e) {
            log.warn("长期记忆读取失败, 本次不带记忆块, userId: {}", userId, e);
            return AgentMemorySnapshot.empty();
        }
    }

    /**
     * 当前有效条目，按 create_time、id 升序：注入、仲裁、容量淘汰都按这个顺序
     */
    public List<AgentMemoryItem> listActiveItems(String userId) {
        return memoryMapper.selectList(Wrappers.lambdaQuery(AgentMemoryDO.class)
                        .eq(AgentMemoryDO::getUserId, userId)
                        .isNull(AgentMemoryDO::getInvalidAt)
                        .orderByAsc(AgentMemoryDO::getCreateTime)
                        .orderByAsc(AgentMemoryDO::getId))
                .stream()
                .map(row -> new AgentMemoryItem(row.getId(), row.getContent(), row.getSourceMessageId(), row.getExpressedAt()))
                .toList();
    }

    /**
     * 本轮请求那条用户发言，必须是该用户自己的用户消息，对不上返回 null
     */
    public AgentMessageDO loadUserMessage(String userId, String messageId) {
        return messageMapper.selectOne(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getId, messageId)
                .eq(AgentMessageDO::getUserId, userId)
                .eq(AgentMessageDO::getRole, ROLE_USER));
    }

    /**
     * 同一对话里这条消息之前最近的几条完整消息，按先后返回，给主动整理理解指代
     */
    public List<AgentMessageDO> loadRecentBefore(String userId, String conversationId, String beforeMessageId, int limit) {
        List<AgentMessageDO> recent = new ArrayList<>(messageMapper.selectList(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getUserId, userId)
                .eq(AgentMessageDO::getConversationId, conversationId)
                .in(AgentMessageDO::getRole, ROLE_USER, ROLE_ASSISTANT)
                .lt(AgentMessageDO::getId, beforeMessageId)
                .orderByDesc(AgentMessageDO::getId)
                .last("LIMIT " + limit)));
        Collections.reverse(recent);
        return recent;
    }

    /**
     * 后台游标：首次纳入时建，已有就不动；调用方须保证在本轮用户发言入库之前调用
     */
    public void ensureCursor(String userId) {
        if (cursorMapper.selectByUserId(userId) == null) {
            cursorMapper.insertIfAbsent(userId);
        }
    }

    /**
     * 后台游标：只读既定游标，没有就返回 null，不在这里补建
     */
    public AgentMemoryCursorDO loadCursor(String userId) {
        return cursorMapper.selectByUserId(userId);
    }

    /**
     * 某条消息之后该用户的发言，跨会话按 id 升序，最多 limit 条；afterMessageId 为空即从第一条开始
     * 后台拿它取游标之后的待整理发言，主动整理拿它看请求之后用户又说了什么
     * id 是同长度的雪花串，字符串序即时间序
     */
    public List<AgentMessageDO> loadUserMessagesAfter(String userId, String afterMessageId, int limit) {
        return messageMapper.selectList(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getUserId, userId)
                .eq(AgentMessageDO::getRole, ROLE_USER)
                .gt(afterMessageId != null, AgentMessageDO::getId, afterMessageId)
                .orderByAsc(AgentMessageDO::getId)
                .last("LIMIT " + limit));
    }

    /**
     * 理解本批发言所需的背景：各段对话分别取原文，去掉本批自己，再按消息先后合回一条
     * 背景不止于本批之前，本批发言之间的助手回复同样在内，但都不能当决策来源
     */
    public List<AgentMessageDO> loadBackground(String userId, List<AgentMessageDO> batch) {
        Map<String, List<AgentMessageDO>> byConversation = new LinkedHashMap<>();
        for (AgentMessageDO message : batch) {
            byConversation.computeIfAbsent(message.getConversationId(), key -> new ArrayList<>()).add(message);
        }
        List<AgentMessageDO> background = new ArrayList<>();
        for (List<AgentMessageDO> messages : byConversation.values()) {
            background.addAll(loadConversation(userId, messages));
        }
        Set<String> batchIds = new HashSet<>();
        batch.forEach(message -> batchIds.add(message.getId()));
        background.removeIf(message -> batchIds.contains(message.getId()));
        background.sort(Comparator.comparing(AgentMessageDO::getId));
        return background;
    }

    /**
     * 一段对话里的取数范围：从本批之前最近的那条用户发言（已整理过，连同它得到的助手回复作背景）取起，
     * 止于本批最后一条用户发言；那之后的助手回复是对新发言的回应，不拿来当新发言的依据
     */
    private List<AgentMessageDO> loadConversation(String userId, List<AgentMessageDO> batch) {
        String conversationId = batch.get(0).getConversationId();
        AgentMessageDO previousUser = messageMapper.selectOne(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getUserId, userId)
                .eq(AgentMessageDO::getConversationId, conversationId)
                .eq(AgentMessageDO::getRole, ROLE_USER)
                .lt(AgentMessageDO::getId, batch.get(0).getId())
                .orderByDesc(AgentMessageDO::getId)
                .last("LIMIT 1"));
        return messageMapper.selectList(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getUserId, userId)
                .eq(AgentMessageDO::getConversationId, conversationId)
                .in(AgentMessageDO::getRole, ROLE_USER, ROLE_ASSISTANT)
                .ge(previousUser != null, AgentMessageDO::getId, previousUser == null ? null : previousUser.getId())
                .le(AgentMessageDO::getId, batch.get(batch.size() - 1).getId())
                .orderByAsc(AgentMessageDO::getId));
    }

    /**
     * 主动整理的提交：只写本次记忆变更，不碰后台游标
     */
    @Transactional(rollbackFor = Exception.class)
    public void commitFlush(String userId, AgentMemoryEdit edit) {
        applyEdit(userId, AgentMemorySourceType.FLUSH, edit);
    }

    /**
     * 后台学习的提交：游标还停在 expectedCursor 才推进到 nextCursor，再写本批变更，两件事同一个事务
     * 游标没推动返回 false 且什么都没写
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean commitBackground(String userId, String expectedCursor, String nextCursor, AgentMemoryEdit edit) {
        if (cursorMapper.advance(userId, expectedCursor, nextCursor) != 1) {
            return false;
        }
        applyEdit(userId, AgentMemorySourceType.BACKGROUND, edit);
        return true;
    }

    /**
     * 记忆写入只有这一份，运行在调用方的事务里；任何一步落空都抛出，整批回滚
     */
    private void applyEdit(String userId, AgentMemorySourceType sourceType, AgentMemoryEdit edit) {
        for (String id : edit.deleteIds()) {
            requireHit(memoryMapper.invalidate(userId, id), "删除 " + id);
        }
        for (AgentMemoryEdit.Update update : edit.updates()) {
            requireHit(memoryMapper.updateContent(userId, update.id(), update.content(), sourceType.name(),
                    update.sourceMessageId(), update.expressedAt()), "修改 " + update.id());
        }
        for (AgentMemoryEdit.Addition addition : edit.additions()) {
            insert(userId, addition, sourceType);
        }
    }

    private void insert(String userId, AgentMemoryEdit.Addition addition, AgentMemorySourceType sourceType) {
        int insert = memoryMapper.insert(AgentMemoryDO.builder()
                .userId(userId)
                .content(addition.content())
                .sourceType(sourceType.name())
                .sourceMessageId(addition.sourceMessageId())
                .expressedAt(addition.expressedAt())
                .build());
        requireHit(insert, "新增");
    }

    /**
     * 条件更新没命中，说明目标不存在、不属于该用户或已经失效，这批不能只写一半
     */
    private static void requireHit(int actual, String what) {
        if (actual != 1) {
            throw new IllegalStateException("长期记忆" + what + "影响行数 " + actual + ", 预期 1");
        }
    }
}

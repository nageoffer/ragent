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

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 主动整理：用户在对话里要求记住、修改、忘掉或清空时，只处理本轮这条请求
 * 拿用户锁 → 读本轮请求、同会话之前几条消息、请求之后用户又说的话、当前有效记忆 → 一次 Judge → 一个事务提交 → 放锁
 * 不读后台游标、不碰后台积压：后台此前没整理的发言仍由后台学习自己处理
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentMemoryFlusher {

    /**
     * 本轮请求之前取几条同会话消息作背景，够理解「这个记住」「对，就是这个」的指代
     */
    private static final int BACKGROUND_MESSAGES = 6;

    /**
     * 请求之后最多看几条用户发言：工具通常在本轮内执行，只有别的对话并行说话时才会有
     */
    private static final int LATER_MESSAGES = 20;

    private final AgentMemoryLock memoryLock;
    private final AgentMemoryRepository memoryRepository;
    private final AgentMemoryJudge memoryJudge;
    private final AgentMemoryProperties memoryProperties;

    public AgentMemoryOutcome flush(String userId, String requestMessageId) {
        if (!memoryProperties.isLongTermEnabled()) {
            return AgentMemoryOutcome.of(Status.DISABLED);
        }
        RLock lock = memoryLock.tryAcquire(userId);
        if (lock == null) {
            return AgentMemoryOutcome.of(Status.BUSY);
        }
        try {
            return flushLocked(userId, requestMessageId);
        } finally {
            memoryLock.release(lock, userId);
        }
    }

    /**
     * flush 后重读生效条目，就地刷新本轮快照；开关关闭时与注入一致给空快照，开启时读不到应抛异常而非退空
     */
    public AgentMemorySnapshot reloadSnapshot(String userId) {
        if (!memoryProperties.isLongTermEnabled()) {
            return AgentMemorySnapshot.empty();
        }
        return new AgentMemorySnapshot(memoryRepository.listActiveItems(userId));
    }

    /**
     * 模型调用在事务外，事务只包提交那一下
     */
    private AgentMemoryOutcome flushLocked(String userId, String requestMessageId) {
        AgentMemoryJudgement judgement;
        AgentMemoryEdit edit;
        try {
            AgentMessageDO request = memoryRepository.loadUserMessage(userId, requestMessageId);
            if (request == null) {
                log.warn("长期记忆主动整理找不到本轮请求, userId: {}, 消息: {}", userId, requestMessageId);
                return AgentMemoryOutcome.of(Status.FAILED);
            }
            List<AgentMessageDO> background = memoryRepository.loadRecentBefore(
                    userId, request.getConversationId(), request.getId(), BACKGROUND_MESSAGES);
            // 请求较早、执行较晚时，用户可能已在别处更正或清空过；只拿来约束本次判断，不替后台消费
            List<AgentMessageDO> later = memoryRepository.loadUserMessagesAfter(userId, request.getId(), LATER_MESSAGES);
            List<AgentMemoryItem> active = memoryRepository.listActiveItems(userId);
            judgement = memoryJudge.judgeFlush(active, request, background, later);
            edit = AgentMemoryEdit.plan(active, judgement.decisions(), memoryProperties.resolveMemoryMaxChars());
        } catch (AgentMemoryCapacityException e) {
            log.warn("长期记忆本次新内容超出容量, 未提交, userId: {}, 渲染字符: {}, 上限: {}",
                    userId, e.getProjectedChars(), e.getMaxChars());
            return AgentMemoryOutcome.of(Status.CAPACITY_EXCEEDED);
        } catch (Exception e) {
            log.warn("长期记忆主动整理读取、仲裁或决策校验失败, 未提交, userId: {}", userId, e);
            return AgentMemoryOutcome.of(Status.FAILED);
        }

        if (!edit.isEmpty()) {
            try {
                memoryRepository.commitFlush(userId, edit);
            } catch (Exception e) {
                // 事务内抛出通常已回滚，但提交那一刻出的错说不准，一律按无法确认如实报
                log.error("长期记忆主动整理提交抛出异常, 是否已写入无法确认, userId: {}", userId, e);
                return AgentMemoryOutcome.of(Status.UNKNOWN);
            }
        }
        return AgentMemoryOutcome.committed(edit, judgement);
    }
}

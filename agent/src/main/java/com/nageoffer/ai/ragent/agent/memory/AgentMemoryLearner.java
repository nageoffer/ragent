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
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryCursorDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 后台学习：每轮结束后异步尝试一次，从用户说过的话里自动发现值得长期记住的信息
 * 拿用户锁 → 读后台游标之后的未整理发言（按用户累计，够三条才继续，一批最多 MAX_MESSAGES 条）→ 补所在对话的原文 → 一次 Judge
 * → 一个事务里提交变更并把游标推到这批最后一条 → 放锁；这批之后的留给下次触发
 * 主动整理过的发言照样会在这里被重读，靠 Judge 对照已有记忆与表达先后算出差异，不重复、不倒退
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentMemoryLearner {

    /**
     * 一批最多取这么多条未整理发言，是查询范围，不是输入长度保护
     */
    private static final int MAX_MESSAGES = 50;

    private final AgentMemoryLock memoryLock;
    private final AgentMemoryRepository memoryRepository;
    private final AgentMemoryJudge memoryJudge;
    private final AgentMemoryProperties memoryProperties;

    /**
     * 聊天入口在本轮用户发言入库之前调用：首次纳入时建游标，界线由这一刻决定，与后面哪一轮先整理无关
     * 失败只留痕不拦对话，代价是该用户要等下一轮入口建好游标才开始后台学习
     */
    public void ensureCursor(String userId) {
        if (!memoryProperties.isLongTermEnabled()) {
            return;
        }
        try {
            memoryRepository.ensureCursor(userId);
        } catch (Exception e) {
            log.warn("长期记忆游标初始化失败, 本轮发言暂不进后台学习, userId: {}", userId, e);
        }
    }

    /**
     * 拿不到锁就留给下次触发
     */
    public AgentMemoryOutcome learn(String userId) {
        if (!memoryProperties.isLongTermEnabled()) {
            return AgentMemoryOutcome.of(Status.DISABLED);
        }
        RLock lock = memoryLock.tryAcquire(userId);
        if (lock == null) {
            return AgentMemoryOutcome.of(Status.BUSY);
        }
        try {
            return learnLocked(userId);
        } finally {
            memoryLock.release(lock, userId);
        }
    }

    /**
     * 模型调用在事务外，事务只包提交那一下；失败不推游标，等下次正常触发再试
     */
    private AgentMemoryOutcome learnLocked(String userId) {
        String cursor;
        List<AgentMessageDO> messages;
        AgentMemoryJudgement judgement;
        AgentMemoryEdit edit;
        try {
            AgentMemoryCursorDO cursorRow = memoryRepository.loadCursor(userId);
            // 游标只在聊天入口建；缺了说明入口那一下失败了，不拿当前最新一条去补界线
            if (cursorRow == null) {
                log.warn("长期记忆游标缺失, 本次不学习, userId: {}", userId);
                return AgentMemoryOutcome.of(Status.FAILED);
            }
            cursor = cursorRow.getLastMessageId();
            messages = memoryRepository.loadUserMessagesAfter(userId, cursor, MAX_MESSAGES);
            if (messages.isEmpty()) {
                return AgentMemoryOutcome.of(Status.NOTHING_PENDING);
            }
            if (messages.size() < memoryProperties.resolveExtractMinTurns()) {
                return AgentMemoryOutcome.of(Status.BELOW_THRESHOLD);
            }
            List<AgentMemoryItem> active = memoryRepository.listActiveItems(userId);
            List<AgentMessageDO> background = memoryRepository.loadBackground(userId, messages);
            judgement = memoryJudge.judgeBackground(active, messages, background);
            edit = AgentMemoryEdit.plan(active, judgement.decisions(), memoryProperties.resolveMemoryMaxChars());
        } catch (AgentMemoryCapacityException e) {
            log.warn("长期记忆本批新内容超出容量, 未提交, userId: {}, 渲染字符: {}, 上限: {}",
                    userId, e.getProjectedChars(), e.getMaxChars());
            return AgentMemoryOutcome.of(Status.CAPACITY_EXCEEDED);
        } catch (Exception e) {
            log.warn("长期记忆后台学习读取、仲裁或决策校验失败, 未提交, userId: {}", userId, e);
            return AgentMemoryOutcome.of(Status.FAILED);
        }

        String nextCursor = messages.get(messages.size() - 1).getId();
        try {
            if (!memoryRepository.commitBackground(userId, cursor, nextCursor, edit)) {
                log.warn("长期记忆后台游标已被推过, 本批未提交, userId: {}, 读取时游标: {}", userId, cursor);
                return AgentMemoryOutcome.of(Status.FAILED);
            }
        } catch (Exception e) {
            log.error("长期记忆后台提交抛出异常, 是否已写入无法确认, userId: {}", userId, e);
            return AgentMemoryOutcome.of(Status.UNKNOWN);
        }
        log.info("长期记忆后台学习已提交, userId: {}, 本批: {}, 游标: {} -> {}, 内容变更: {}",
                userId, messages.size(), cursor, nextCursor, edit.changes().size());
        return AgentMemoryOutcome.committed(edit, judgement);
    }
}

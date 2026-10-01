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
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryControlDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryExtractionDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryExtractionStatus;
import com.nageoffer.ai.ragent.agent.enums.AgentMemorySourceType;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryTriggerType;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 抽取管道：两个入口共用这一条，只在门槛与失败反馈上分叉
 * 顺序是「读快照 → 不持锁仲裁 → 短事务双校验」，中途的库变更由提交侧兜住
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentMemoryPipeline {

    /**
     * 显式整理一次最多跑几批；每批一次仲裁调用，全压在本轮回答里，跑不完如实报未完成而不是无限续跑
     */
    private static final int MAX_FLUSH_BATCHES = 3;

    private final AgentMemoryRepository memoryRepository;
    private final AgentMemoryJudge memoryJudge;
    private final AgentMemoryConsolidator memoryConsolidator;
    private final AgentMemoryProperties memoryProperties;

    /**
     * 首条消息落库前预建控制行：建行时刻是抽取下界，建晚了本轮消息永久漏抽
     * 失败只留痕不拦对话，代价是本轮消息可能落在下界之外
     */
    public void ensureExtractionBaseline(String userId) {
        if (!memoryProperties.isLongTermEnabled()) {
            return;
        }
        try {
            memoryRepository.ensureControl(userId);
        } catch (Exception e) {
            log.warn("长期记忆控制行预建失败, 本轮消息可能漏出抽取下界, userId: {}", userId, e);
        }
    }

    /**
     * 显式整理：连跑到覆盖本次请求那条消息为止，最多 MAX_FLUSH_BATCHES 批，积压超过一批时本次请求排在后面
     * 判据是覆盖它的那批结成 WRITTEN/NOOP：DROPPED 同样推水位，只看水位会把失败报成成功
     */
    public AgentMemoryOutcome flush(String userId, String conversationId, String requestMessageId) {
        AgentMemoryOutcome outcome = extract(userId, conversationId, AgentMemoryTriggerType.FLUSH);
        for (int batch = 1; batch < MAX_FLUSH_BATCHES && outcome.settled()
                && memoryRepository.settledStatusCovering(userId, requestMessageId) == null; batch++) {
            outcome = outcome.then(extract(userId, conversationId, AgentMemoryTriggerType.FLUSH));
        }
        if (!outcome.settled() && outcome.status() != Status.NOTHING_PENDING) {
            return outcome;
        }
        // 没有待处理不等于处理成功：本次请求可能早被一批 DROPPED 越过，再调一次也不许把那次失败洗成「都整理过了」
        AgentMemoryExtractionStatus covering = memoryRepository.settledStatusCovering(userId, requestMessageId);
        if (covering == null) {
            return outcome.withStatus(Status.INCOMPLETE);
        }
        return covering == AgentMemoryExtractionStatus.DROPPED ? outcome.withStatus(Status.FAILED) : outcome;
    }

    /**
     * 跑完一批；抢不到处理权或没到门槛都算正常结局，不抛异常
     * 批次取该用户所有会话里最早的待处理消息，conversationId 只记触发方
     */
    public AgentMemoryOutcome extract(String userId, String conversationId, AgentMemoryTriggerType trigger) {
        if (!memoryProperties.isLongTermEnabled()) {
            return AgentMemoryOutcome.of(Status.DISABLED, 0);
        }
        AgentMemoryControlDO control = memoryRepository.ensureControl(userId);
        String watermark = memoryRepository.currentWatermark(userId);
        List<AgentMessageDO> pending = memoryRepository.loadPending(userId, watermark, control.getCreateTime());
        if (pending.isEmpty()) {
            return AgentMemoryOutcome.of(Status.NOTHING_PENDING, 0);
        }
        if (trigger == AgentMemoryTriggerType.BACKGROUND
                && pending.size() < memoryProperties.resolveExtractMinTurns()) {
            return AgentMemoryOutcome.of(Status.BELOW_THRESHOLD, pending.size());
        }

        AgentMemoryExtractionDO extraction = memoryRepository.claim(userId, conversationId,
                pending.get(0).getId(), pending.get(pending.size() - 1).getId(), trigger);
        if (extraction == null) {
            return AgentMemoryOutcome.of(Status.BUSY, pending.size());
        }
        return runExtraction(userId, conversationId, trigger, control, watermark, pending, extraction);
    }

    /**
     * 抢到处理权之后的主体；仲裁在事务外跑，慢调用不占着控制面行锁
     */
    private AgentMemoryOutcome runExtraction(String userId, String conversationId, AgentMemoryTriggerType trigger,
                                             AgentMemoryControlDO control, String watermark,
                                             List<AgentMessageDO> pending, AgentMemoryExtractionDO extraction) {
        List<AgentMemoryItem> existing;
        List<AgentMemoryDecision> decisions;
        try {
            // 读快照也要收在这道边界里，抢到 claim 之后任何一步漏出去都会把台账行挂在 PROCESSING 上
            existing = memoryRepository.listActiveItems(userId);
            decisions = memoryJudge.judge(existing, pending);
        } catch (Exception e) {
            AgentMemoryExtractionStatus settled = memoryRepository.settleFailure(extraction);
            log.warn("长期记忆读取或仲裁失败, extractionId: {}, 结算: {}", extraction.getId(), settled, e);
            return AgentMemoryOutcome.of(Status.FAILED, pending.size());
        }

        AgentMemoryCommit commit = new AgentMemoryCommit(userId, extraction.getId(),
                extraction.getAttemptCount(), control.getRevision(), watermark, sourceTypeOf(trigger),
                decisions, planConsolidation(existing, decisions));
        try {
            return toOutcome(memoryRepository.commit(commit), pending.size());
        } catch (AgentMemoryCapacityException e) {
            // 可预见的结局不是缺陷，不留堆栈；淘汰已随事务一并回滚，重试阶梯照常走
            AgentMemoryExtractionStatus settled = memoryRepository.settleFailure(extraction);
            log.warn("长期记忆容量拒收, extractionId: {}, 结算: {}, 预演字符: {}, 上限: {}",
                    extraction.getId(), settled, e.getProjectedChars(), e.getMaxChars());
            return AgentMemoryOutcome.of(Status.CAPACITY_REJECTED, pending.size());
        } catch (Exception e) {
            // 事务已回滚但台账行是事务外插的，不补一刀结算就会一直挂在 PROCESSING 上堵到僵尸回收
            AgentMemoryExtractionStatus settled = memoryRepository.settleFailure(extraction);
            log.error("长期记忆提交异常, extractionId: {}, 结算: {}", extraction.getId(), settled, e);
            return AgentMemoryOutcome.of(Status.FAILED, pending.size());
        }
    }

    /**
     * 只有预演顶到上限才多叫这一次模型；这里读的是无锁快照，权威判定仍在提交事务里
     * 预检看走眼最多是少了这次合并、提交侧改用淘汰腾位置，下一批重新预演时照样会触发合并
     */
    private List<AgentMemoryMerge> planConsolidation(List<AgentMemoryItem> existing,
                                                     List<AgentMemoryDecision> decisions) {
        // 清空批里旧条目马上整片失效，合并它们是白叫一次模型；清空后新增装不下由提交侧整批拒收
        if (AgentMemoryDecision.containsClear(decisions)) {
            return List.of();
        }
        int maxChars = memoryProperties.resolveMemoryMaxChars();
        int projected = AgentMemoryBlock.projectedChars(existing, decisions);
        if (projected <= maxChars) {
            return List.of();
        }
        log.info("长期记忆容量顶到上限, 先试一次受限合并, 预演字符: {}, 上限: {}, 目标: {}",
                projected, maxChars, memoryProperties.resolveConsolidationStopChars());
        return memoryConsolidator.plan(existing);
    }

    private AgentMemoryOutcome toOutcome(AgentMemoryCommitResult result, int pending) {
        Status status = switch (result.status()) {
            case WRITTEN -> Status.WRITTEN;
            case NOOP -> Status.SETTLED_EMPTY;
            default -> Status.CONFLICT;
        };
        return new AgentMemoryOutcome(status, result.applied(), pending, result.mutated(),
                result.cleared(), result.clearedItems());
    }

    private AgentMemorySourceType sourceTypeOf(AgentMemoryTriggerType trigger) {
        return trigger == AgentMemoryTriggerType.FLUSH
                ? AgentMemorySourceType.FLUSH
                : AgentMemorySourceType.BACKGROUND;
    }

    /**
     * 提交后重读生效条目，flush 成功时就地刷新快照；与 loadSnapshot 不同：读不到应抛异常而非退空
     */
    public AgentMemorySnapshot reloadSnapshot(String userId) {
        return new AgentMemorySnapshot(memoryRepository.listActiveItems(userId));
    }
}

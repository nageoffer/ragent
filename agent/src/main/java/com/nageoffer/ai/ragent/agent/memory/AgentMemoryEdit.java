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

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一批决策作用到当前记忆上的结果：先在内存里算清要写什么、清单怎么报，再交给事务一次写完
 * 先后与冲突由 Judge 判断，这里只校验能不能照做：指错条目、同批重复指认都整批拒绝，不替 Judge 改判
 * 校验通过后去掉没有数据差异的完全重复操作，只写实际变化
 *
 * @param deleteIds 失效的条目：用户要求删除的，以及为腾出容量移除的
 * @param updates   原行改写正文与依据；正文不变时只换依据，不进变更清单
 * @param changes   提交成功后反馈给用户的内容变更清单；只换依据的修改不在其中，所以有写入时也可能为空
 */
public record AgentMemoryEdit(List<String> deleteIds,
                              List<Update> updates,
                              List<Addition> additions,
                              List<String> changes) {

    public record Addition(String content, String sourceMessageId, Date expressedAt) {
    }

    public record Update(String id, String content, String sourceMessageId, Date expressedAt) {
    }

    /**
     * active 须按 create_time、id 升序，容量淘汰从最早写入的开始
     * 与目标或提交后已有条目完全相同（正文、来源消息、表达时间都一样）的改写和新增没有数据差异，不写也不进清单
     */
    static AgentMemoryEdit plan(List<AgentMemoryItem> active, List<AgentMemoryDecision> decisions, int maxChars) {
        Map<String, AgentMemoryItem> untouched = new LinkedHashMap<>();
        active.forEach(item -> untouched.put(item.id(), item));
        Set<String> targeted = new HashSet<>();
        List<String> deleteIds = new ArrayList<>();
        List<Update> updates = new ArrayList<>();
        List<Addition> additions = new ArrayList<>();
        List<String> changes = new ArrayList<>();
        for (AgentMemoryDecision decision : decisions) {
            switch (decision.action()) {
                case ADD -> additions.add(new Addition(decision.content(),
                        decision.sourceMessageId(), decision.expressedAt()));
                case UPDATE -> {
                    AgentMemoryItem old = requireTarget(untouched, targeted, decision.targetId());
                    String content = decision.content();
                    if (sameFact(old, content, decision.sourceMessageId(), decision.expressedAt())) {
                        continue;
                    }
                    untouched.remove(old.id());
                    updates.add(new Update(old.id(), content, decision.sourceMessageId(), decision.expressedAt()));
                    if (!content.equals(old.content())) {
                        changes.add("修改：" + old.content() + " → " + content);
                    }
                }
                case DELETE -> {
                    AgentMemoryItem old = requireTarget(untouched, targeted, decision.targetId());
                    untouched.remove(old.id());
                    deleteIds.add(old.id());
                    changes.add("删除：" + old.content());
                }
            }
        }

        // 新增要对照这批之后仍然有效的条目：没改动的旧条目、改写后的结果、这批已经收下的新增；被删掉或改走的旧条目不算
        List<AgentMemoryItem> kept = new ArrayList<>(untouched.values());
        updates.forEach(update -> kept.add(new AgentMemoryItem(update.id(), update.content(),
                update.sourceMessageId(), update.expressedAt())));
        List<Addition> newAdditions = new ArrayList<>();
        for (Addition addition : additions) {
            if (kept.stream().anyMatch(item -> sameFact(item, addition.content(), addition.sourceMessageId(), addition.expressedAt()))) {
                continue;
            }
            newAdditions.add(addition);
            kept.add(new AgentMemoryItem(null, addition.content(), addition.sourceMessageId(), addition.expressedAt()));
            changes.add("新增：" + addition.content());
        }

        List<String> written = new ArrayList<>();
        updates.forEach(update -> written.add(update.content()));
        newAdditions.forEach(addition -> written.add(addition.content()));

        // 只淘汰这批没有改动的条目，按 create_time 最早写入的先走；原行改写不改 create_time
        int chars = renderedChars(untouched, written);
        for (AgentMemoryItem oldest : new ArrayList<>(untouched.values())) {
            if (chars <= maxChars) {
                break;
            }
            untouched.remove(oldest.id());
            deleteIds.add(oldest.id());
            changes.add("容量已满，移除最早的一条：" + oldest.content());
            chars = renderedChars(untouched, written);
        }
        if (chars > maxChars) {
            throw new AgentMemoryCapacityException(chars, maxChars);
        }
        return new AgentMemoryEdit(deleteIds, updates, newAdditions, changes);
    }

    boolean isEmpty() {
        return deleteIds.isEmpty() && updates.isEmpty() && additions.isEmpty();
    }

    /**
     * 改写、删除只能指向当前有效、且这批还没指认过的条目；指不着就是这批判错了
     */
    private static AgentMemoryItem requireTarget(Map<String, AgentMemoryItem> untouched, Set<String> targeted, String targetId) {
        AgentMemoryItem target = untouched.get(targetId);
        if (target == null || !targeted.add(targetId)) {
            throw new IllegalStateException("长期记忆决策指向的条目不在当前有效记忆里, 或同批被重复指向: " + targetId);
        }
        return target;
    }

    /**
     * 正文、来源消息、表达时间都一样才算同一条；表达时间比毫秒值而不是秒级字符串
     * 不用 equals：库里读出的可能是 Timestamp，Timestamp.equals(Date) 恒为 false
     */
    private static boolean sameFact(AgentMemoryItem item, String content, String sourceMessageId, Date expressedAt) {
        return content.equals(item.content())
                && Objects.equals(sourceMessageId, item.sourceMessageId())
                && expressedAt != null && item.expressedAt() != null
                && expressedAt.getTime() == item.expressedAt().getTime();
    }

    /**
     * 与注入同一口径：按提交后的整块记忆渲染计长
     */
    private static int renderedChars(Map<String, AgentMemoryItem> untouched, List<String> written) {
        List<String> contents = new ArrayList<>();
        untouched.values().forEach(item -> contents.add(item.content()));
        contents.addAll(written);
        return AgentMemoryBlock.render(contents).length();
    }
}

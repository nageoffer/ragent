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

import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryDecision.Action;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 决策落到当前记忆上的计算：清单只列真正会写的变化，不合要求整批拒绝，不按消息先后替 Judge 改判，容量从最早写入的未改动条目淘汰
 */
class AgentMemoryEditTest {

    private static final int ROOMY = 6000;

    private static final List<AgentMemoryItem> ACTIVE = List.of(
            item("m-1", "用户住在上海", "1900000000000000001"),
            item("m-2", "用户喜欢红色", "1900000000000000002"),
            item("m-3", "用户是工程师", "1900000000000000003"));

    private static final AgentMessageDO LATER = source("1900000000000000009");

    @Test
    void shouldListAddsReplacementsAndDeletions() {
        AgentMemoryEdit edit = AgentMemoryEdit.plan(ACTIVE, List.of(
                add("用户喜欢咖啡", LATER),
                update("m-1", "用户住在南京", LATER),
                delete("m-2", LATER)), ROOMY);

        assertThat(edit.changes()).containsExactly(
                "修改：用户住在上海 → 用户住在南京", "删除：用户喜欢红色", "新增：用户喜欢咖啡");
        assertThat(edit.additions()).containsExactly(new AgentMemoryEdit.Addition("用户喜欢咖啡", LATER.getId(), LATER.getCreateTime()));
        assertThat(edit.updates()).containsExactly(new AgentMemoryEdit.Update("m-1", "用户住在南京", LATER.getId(), LATER.getCreateTime()));
        assertThat(edit.deleteIds()).containsExactly("m-2");
    }

    @Test
    void noDecisionShouldWriteNothing() {
        AgentMemoryEdit edit = AgentMemoryEdit.plan(ACTIVE, List.of(), ROOMY);

        assertThat(edit.changes()).isEmpty();
        assertThat(edit.isEmpty()).isTrue();
    }

    /**
     * 正文不变的改写只换依据：照样原行写库，但不进变更清单，也不新增重复条目
     */
    @Test
    void updateWithSameContentShouldOnlyRefreshSource() {
        AgentMemoryEdit edit = AgentMemoryEdit.plan(ACTIVE, List.of(update("m-1", "用户住在上海", LATER)), ROOMY);

        assertThat(edit.updates()).containsExactly(new AgentMemoryEdit.Update("m-1", "用户住在上海", LATER.getId(), LATER.getCreateTime()));
        assertThat(edit.additions()).isEmpty();
        assertThat(edit.changes()).isEmpty();
        assertThat(edit.isEmpty()).isFalse();
    }

    /**
     * 先后由 Judge 判断：依据的消息比目标条目早，程序也照做，不静默跳过后再拿 Judge 的说明报成功
     */
    @Test
    void shouldNotSecondGuessJudgeByMessageOrder() {
        AgentMemoryEdit edit = AgentMemoryEdit.plan(ACTIVE,
                List.of(delete("m-3", source("1900000000000000002"))), ROOMY);

        assertThat(edit.deleteIds()).containsExactly("m-3");
        assertThat(edit.changes()).containsExactly("删除：用户是工程师");
    }

    /**
     * 「清空我的长期记忆，然后记住我常驻杭州」：清空折算成逐条删除，同句里要记的照常新增
     */
    @Test
    void clearShouldBeExpressedAsDeletesPlusAdds() {
        AgentMessageDO sentence = source("1900000000000000010");

        AgentMemoryEdit edit = AgentMemoryEdit.plan(ACTIVE, List.of(
                delete("m-1", sentence), delete("m-2", sentence), delete("m-3", sentence),
                add("用户常驻杭州", sentence)), ROOMY);

        assertThat(edit.deleteIds()).containsExactly("m-1", "m-2", "m-3");
        assertThat(edit.changes()).containsExactly(
                "删除：用户住在上海", "删除：用户喜欢红色", "删除：用户是工程师", "新增：用户常驻杭州");
    }

    @Test
    void shouldRejectWholeBatchWhenTargetIsNotActive() {
        assertThatThrownBy(() -> AgentMemoryEdit.plan(ACTIVE, List.of(
                add("用户戴眼镜", LATER),
                delete("unknown", LATER)), ROOMY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void shouldRejectWholeBatchWhenTargetIsUsedTwice() {
        assertThatThrownBy(() -> AgentMemoryEdit.plan(ACTIVE, List.of(
                update("m-1", "用户住在南京", LATER),
                delete("m-1", LATER)), ROOMY))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 装不下时从最早写入、这批没改动的条目淘汰；这批改写的条目不会被当成淘汰对象
     */
    @Test
    void shouldEvictOldestUntouchedItemsToMakeRoom() {
        List<AgentMemoryItem> active = List.of(
                item("m-1", "旧".repeat(100), "1900000000000000001"),
                item("m-2", "二".repeat(100), "1900000000000000002"),
                item("m-3", "三".repeat(100), "1900000000000000003"));
        int maxChars = AgentMemoryBlock.render(active.stream().map(AgentMemoryItem::content).toList()).length();

        AgentMemoryEdit edit = AgentMemoryEdit.plan(active, List.of(
                update("m-1", "新".repeat(100), LATER),
                add("加".repeat(100), LATER)), maxChars);

        assertThat(edit.deleteIds()).containsExactly("m-2");
        assertThat(edit.changes()).last().isEqualTo("容量已满，移除最早的一条：" + "二".repeat(100));
    }

    @Test
    void shouldFailWhenNewContentAloneExceedsCapacity() {
        List<AgentMemoryDecision> decisions = List.of(
                add("甲".repeat(400), LATER), add("乙".repeat(400), LATER));

        assertThatThrownBy(() -> AgentMemoryEdit.plan(List.of(), decisions, 600))
                .isInstanceOf(AgentMemoryCapacityException.class);
    }

    /**
     * 现场：M6 主动整理已写好，后台重读 M6 又给出同正文同来源的 UPDATE 和 ADD，最终什么都不写
     * 库里读出的表达时间是 Timestamp，来源侧是 Date，同一毫秒也要认成同一条
     */
    @Test
    void replayOfAlreadyAppliedFlushShouldWriteNothing() {
        AgentMessageDO m6 = AgentMessageDO.builder().id("1900000000000000060").createTime(new Date(60_081)).build();
        List<AgentMemoryItem> active = List.of(
                new AgentMemoryItem("phone", "用户喜欢大屏手机。", m6.getId(), new Timestamp(60_081)),
                new AgentMemoryItem("pad", "用户一直更喜欢大屏平板。", "1900000000000000050", new Timestamp(50_000)),
                new AgentMemoryItem("city", "用户常驻杭州。", m6.getId(), new Timestamp(60_081)));

        AgentMemoryEdit edit = AgentMemoryEdit.plan(active, List.of(
                update("phone", "用户喜欢大屏手机。", m6), add("用户常驻杭州。", m6)), ROOMY);

        assertThat(edit.isEmpty()).isTrue();
        assertThat(edit.changes()).isEmpty();
    }

    /**
     * 同批重复的同一条新增只写一次；同一句话说出的不同事实各写各的
     */
    @Test
    void duplicateAddsInBatchShouldCollapseButDifferentFactsFromSameSourceStay() {
        AgentMemoryEdit edit = AgentMemoryEdit.plan(List.of(), List.of(
                add("用户常驻杭州", LATER), add("用户常驻杭州", LATER), add("用户戴眼镜", LATER)), ROOMY);

        assertThat(edit.additions()).extracting(AgentMemoryEdit.Addition::content).containsExactly("用户常驻杭州", "用户戴眼镜");
        assertThat(edit.changes()).containsExactly("新增：用户常驻杭州", "新增：用户戴眼镜");
    }

    /**
     * 这批删掉的条目不算仍然存在：删除后按同一来源重新保存照常新增
     */
    @Test
    void addAfterDeleteInSameBatchShouldNotBeFilteredByOldSnapshot() {
        AgentMessageDO same = source("1900000000000000001");
        List<AgentMemoryItem> active = List.of(new AgentMemoryItem("m-1", "用户住在上海", same.getId(), same.getCreateTime()));

        AgentMemoryEdit edit = AgentMemoryEdit.plan(active, List.of(delete("m-1", same), add("用户住在上海", same)), ROOMY);

        assertThat(edit.deleteIds()).containsExactly("m-1");
        assertThat(edit.additions()).extracting(AgentMemoryEdit.Addition::content).containsExactly("用户住在上海");
    }

    /**
     * 无变化的改写不算改动：容量淘汰照常从最早的条目开始，它本身也可能被淘汰；同一目标重复指认仍整批拒绝
     */
    @Test
    void noOpUpdateShouldNotProtectFromEvictionAndStillCountsAsTargeted() {
        List<AgentMemoryItem> active = List.of(
                item("m-1", "一".repeat(100), "1900000000000000001"),
                item("m-2", "二".repeat(100), "1900000000000000002"));
        int maxChars = AgentMemoryBlock.render(active.stream().map(AgentMemoryItem::content).toList()).length();
        AgentMessageDO own = source("1900000000000000001");

        AgentMemoryEdit edit = AgentMemoryEdit.plan(active, List.of(
                update("m-1", "一".repeat(100), own), add("加".repeat(100), LATER)), maxChars);

        assertThat(edit.updates()).isEmpty();
        assertThat(edit.deleteIds()).containsExactly("m-1");
        assertThatThrownBy(() -> AgentMemoryEdit.plan(active, List.of(
                update("m-1", "一".repeat(100), own), delete("m-1", LATER)), ROOMY))
                .isInstanceOf(IllegalStateException.class);
    }

    static AgentMemoryDecision add(String content, AgentMessageDO source) {
        return new AgentMemoryDecision(Action.ADD, null, content, source.getId(), source.getCreateTime());
    }

    static AgentMemoryDecision update(String targetId, String content, AgentMessageDO source) {
        return new AgentMemoryDecision(Action.UPDATE, targetId, content, source.getId(), source.getCreateTime());
    }

    static AgentMemoryDecision delete(String targetId, AgentMessageDO source) {
        return new AgentMemoryDecision(Action.DELETE, targetId, null, source.getId(), source.getCreateTime());
    }

    private static AgentMemoryItem item(String id, String content, String sourceMessageId) {
        return new AgentMemoryItem(id, content, sourceMessageId, new Date(0));
    }

    static AgentMessageDO source(String messageId) {
        return AgentMessageDO.builder().id(messageId).createTime(new Date(0)).build();
    }
}

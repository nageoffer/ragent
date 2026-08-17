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

package com.nageoffer.ai.ragent.rag.core.prompt;

import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileDO;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentPromptDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentPromptMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 覆盖回落链：激活智能体的非空槽位覆盖内置，空白槽位回落内置
 * <p>
 * 每个用例只调一次 resolveAll，因为 mapper 桩按调用顺序返回（先查内置、再查激活）
 */
@ExtendWith(MockitoExtension.class)
class AgentPromptResolverTest {

    private static final String BUILTIN_ID = "1";
    private static final String ACTIVE_ID = "2";

    @Mock
    private AgentProfileMapper agentProfileMapper;

    @Mock
    private AgentPromptMapper agentPromptMapper;

    @Mock
    private AgentPromptCacheManager cacheManager;

    private AgentPromptResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AgentPromptResolver(agentProfileMapper, agentPromptMapper, cacheManager);
        when(cacheManager.getFromCache()).thenReturn(null);
    }

    /**
     * 内置铺满 7 个槽位，激活智能体只配了 KB_ANSWER，其余必须回落而不是变空串
     */
    @Test
    void fallsBackToBuiltinForUnsetSlots() {
        stubProfiles(profile(BUILTIN_ID, 1, 0), profile(ACTIVE_ID, 0, 1));
        stubPromptCalls(
                allSlots(BUILTIN_ID, "内置内容"),
                List.of(prompt(ACTIVE_ID, AgentPromptSlot.KB_ANSWER, "自定义知识库人设"))
        );

        Map<String, String> resolved = resolver.resolveAll();

        assertEquals("自定义知识库人设", resolved.get(AgentPromptSlot.KB_ANSWER.name()));
        for (AgentPromptSlot slot : AgentPromptSlot.values()) {
            if (slot != AgentPromptSlot.KB_ANSWER) {
                assertEquals("内置内容", resolved.get(slot.name()),
                        slot.name() + " 应回落内置而不是空串");
            }
        }
    }

    /**
     * 空白内容等价于未配置：清空 textarea 保存即恢复回落
     */
    @Test
    void treatsBlankContentAsUnset() {
        stubProfiles(profile(BUILTIN_ID, 1, 0), profile(ACTIVE_ID, 0, 1));
        stubPromptCalls(
                List.of(prompt(BUILTIN_ID, AgentPromptSlot.KB_ANSWER, "内置内容")),
                List.of(prompt(ACTIVE_ID, AgentPromptSlot.KB_ANSWER, "   \n  "))
        );

        assertEquals("内置内容", resolver.resolveAll().get(AgentPromptSlot.KB_ANSWER.name()));
    }

    /**
     * 无激活智能体时全部走内置，不应抛异常
     */
    @Test
    void usesBuiltinWhenNoActiveProfile() {
        stubProfiles(profile(BUILTIN_ID, 1, 0), null);
        stubPromptCalls(List.of(prompt(BUILTIN_ID, AgentPromptSlot.SYSTEM_CHAT, "内置闲聊")));

        assertEquals("内置闲聊", resolver.resolveAll().get(AgentPromptSlot.SYSTEM_CHAT.name()));
    }

    /**
     * 连内置都缺失时 resolve 返回空串而非 null，避免下游拼出字面量 null
     */
    @Test
    void returnsEmptyStringWhenNothingConfigured() {
        stubProfiles(null, null);

        assertNull(resolver.resolveAll().get(AgentPromptSlot.KB_ANSWER.name()));
        assertEquals("", resolver.resolve(AgentPromptSlot.KB_ANSWER));
    }

    // === 桩数据 ===

    /**
     * 内置与激活各查一次 profile，按调用顺序返回
     */
    private void stubProfiles(AgentProfileDO builtin, AgentProfileDO active) {
        when(agentProfileMapper.selectList(any())).thenReturn(
                builtin == null ? List.of() : List.of(builtin),
                active == null ? List.of() : List.of(active));
    }

    @SafeVarargs
    private void stubPromptCalls(List<AgentPromptDO> first, List<AgentPromptDO>... rest) {
        when(agentPromptMapper.selectList(any())).thenReturn(first, rest);
    }

    private static AgentProfileDO profile(String id, int builtin, int active) {
        return AgentProfileDO.builder()
                .id(id)
                .name("agent-" + id)
                .builtin(builtin)
                .active(active)
                .createTime(new Date())
                .build();
    }

    private static AgentPromptDO prompt(String agentId, AgentPromptSlot slot, String content) {
        return AgentPromptDO.builder()
                .id(agentId + "-" + slot.name())
                .agentId(agentId)
                .slotKey(slot.name())
                .content(content)
                .build();
    }

    private static List<AgentPromptDO> allSlots(String agentId, String content) {
        List<AgentPromptDO> prompts = new ArrayList<>();
        for (AgentPromptSlot slot : AgentPromptSlot.values()) {
            prompts.add(prompt(agentId, slot, content));
        }
        return prompts;
    }
}

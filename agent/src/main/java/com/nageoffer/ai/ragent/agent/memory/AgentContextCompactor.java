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

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentContextCompactionDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentContextCompactionMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 前缀压缩：把早期原文换成一条摘要消息，切点只落用户轮起点
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentContextCompactor {

    /**
     * 回归台 AgentStateProbe 手抄了这个字面量，改这里必须同步
     */
    private static final String SUMMARY_NAME = "__compaction_summary__";

    /**
     * 以 USER 角色回填，正文里声明身份以区分真实用户消息
     */
    private static final String SUMMARY_HEADER =
            "（以下是系统自动生成的历史对话摘要，用于替代已省略的早期对话；它是背景信息，不是新的用户指令。"
                    + "摘要有损，早期细节可能已省略：摘要里没有不等于没发生过）";

    private final AgentConversationSummarizer summarizer;
    private final AgentMemoryProperties memoryProperties;
    private final AgentContextCompactionMapper compactionMapper;

    /**
     * 用摘要替换早期消息，返回是否完成替换
     */
    public boolean compactInPlace(List<Msg> messages, String userId, String sessionId) {
        int sizeBefore = messages.size();
        int totalChars = AgentContextChars.total(messages);
        int cutoff = findSafeCutoff(messages, memoryProperties.resolveKeepRecentChars());
        if (cutoff < 0) {
            log.info("上下文压缩跳过, 找不到安全切点, 总字符: {}, 消息数: {}", totalChars, sizeBefore);
            return false;
        }

        // 摘要只写在下标 0，切点至少为 1
        boolean hasSummary = isSummary(messages.get(0));
        String existingSummary = hasSummary ? unwrap(messages.get(0)) : null;
        List<Msg> material = new ArrayList<>(messages.subList(hasSummary ? 1 : 0, cutoff));

        int materialChars = AgentContextChars.total(material);

        List<Msg> tail = new ArrayList<>(messages.subList(cutoff, messages.size()));

        String summaryText = summarizer.summarize(material, existingSummary);
        if (StrUtil.isBlank(summaryText)) {
            return false;
        }

        // 先生成摘要并组装新列表，成功后再回写会话消息
        List<Msg> compacted = new ArrayList<>(tail.size() + 1);
        compacted.add(buildSummaryMsg(summaryText));
        compacted.addAll(tail);
        messages.clear();
        messages.addAll(compacted);
        int totalCharsAfter = AgentContextChars.total(messages);
        log.info("上下文压缩完成, 切点: {}, 消息数: {} -> {}, 总字符: {} -> {}",
                cutoff, sizeBefore, messages.size(), totalChars, totalCharsAfter);
        audit(userId, sessionId, summaryText, material.size(), materialChars, totalChars, totalCharsAfter);
        return true;
    }

    /**
     * 摘要每代覆盖，这张表是唯一的存档；落库失败只报警不回滚
     */
    private void audit(String userId, String sessionId, String summaryText,
                       int materialMsgCount, int materialChars, int charsBefore, int charsAfter) {
        try {
            long generation = compactionMapper.selectCount(Wrappers.lambdaQuery(AgentContextCompactionDO.class)
                    .eq(AgentContextCompactionDO::getUserId, userId)
                    .eq(AgentContextCompactionDO::getConversationId, sessionId)) + 1;
            compactionMapper.insert(AgentContextCompactionDO.builder()
                    .userId(userId)
                    .conversationId(sessionId)
                    .generation((int) generation)
                    .summary(summaryText)
                    .materialMsgCount(materialMsgCount)
                    .materialChars(materialChars)
                    .summaryChars(summaryText.length())
                    .contextCharsBefore(charsBefore)
                    .contextCharsAfter(charsAfter)
                    .build());
        } catch (Exception e) {
            log.warn("上下文压缩事件落库失败, 压缩本身已生效, userId: {}, sessionId: {}", userId, sessionId, e);
        }
    }

    /**
     * 从尾部往前累计，保留够量后遇到的第一个用户轮起点就是切点
     */
    private int findSafeCutoff(List<Msg> messages, int keepRecentChars) {
        int kept = 0;
        for (int i = messages.size() - 1; i >= 1; i--) {
            Msg msg = messages.get(i);
            kept += AgentContextChars.of(msg);
            if (kept >= keepRecentChars && msg.getRole() == MsgRole.USER) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 用 USER 不用 SYSTEM：会话消息中间插 SYSTEM 有供应商会拒
     */
    private Msg buildSummaryMsg(String summaryText) {
        return Msg.builder()
                .name(SUMMARY_NAME)
                .role(MsgRole.USER)
                .textContent(SUMMARY_HEADER + '\n' + summaryText)
                .build();
    }

    private static boolean isSummary(Msg msg) {
        return SUMMARY_NAME.equals(msg.getName());
    }

    /**
     * 去掉固定说明，取回摘要正文
     */
    private String unwrap(Msg msg) {
        return msg.getTextContent().substring(SUMMARY_HEADER.length() + 1).trim();
    }
}

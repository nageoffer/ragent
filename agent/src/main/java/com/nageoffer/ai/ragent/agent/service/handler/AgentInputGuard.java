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

package com.nageoffer.ai.ragent.agent.service.handler;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.config.InputGuardProperties;
import com.nageoffer.ai.ragent.rag.core.guard.InputRisk;
import com.nageoffer.ai.ragent.rag.core.guard.InputRiskDetector;
import com.nageoffer.ai.ragent.rag.core.guard.PreviousTurn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Agent 提问入口审核：拒绝与故障都在建会话、写消息之前抛出，由调用方走启动失败的放闸路径
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentInputGuard {

    public static final String BLOCKED_MESSAGE = "抱歉，这个请求我无法处理。";
    public static final String UNAVAILABLE_MESSAGE = "本次未能完成安全检查，请稍后重试。";

    private final InputRiskDetector detector;
    private final AgentConversationService conversationService;
    private final InputGuardProperties properties;

    /**
     * 审核日志不记录用户提问原文
     *
     * @param conversationId 客户端原样传来的会话 ID，空表示首轮，首轮不查历史
     */
    public void check(String taskId, String userId, String conversationId, String question) {
        Supplier<Optional<PreviousTurn>> previousTurn = StrUtil.isBlank(conversationId)
                ? Optional::empty
                : () -> conversationService.findPreviousTurn(conversationId, userId);
        long startNanos = System.nanoTime();
        Set<InputRisk> risks;
        try {
            risks = detector.detect(question, previousTurn);
        } catch (RuntimeException e) {
            record("审核失败", "未判定", startNanos, taskId, userId, conversationId);
            throw new ServiceException(UNAVAILABLE_MESSAGE, e, BaseErrorCode.SERVICE_ERROR);
        }
        if (risks.isEmpty()) {
            String riskSummary = properties.getType() == InputGuardProperties.Type.NONE ? "未检测" : "无";
            record("已放行", riskSummary, startNanos, taskId, userId, conversationId);
            return;
        }
        String riskSummary = risks.stream()
                .sorted()
                .map(InputRisk::getDisplayName)
                .collect(Collectors.joining("、"));
        record("已被拦截", riskSummary, startNanos, taskId, userId, conversationId);
        throw new ClientException(BLOCKED_MESSAGE);
    }

    private void record(String verdict, String riskSummary, long startNanos,
                        String taskId, String userId, String conversationId) {
        log.info("用户请求{}，风险：{}，审核方式：{}，耗时：{}ms，任务ID：{}，用户ID：{}，会话ID：{}",
                verdict, riskSummary, properties.getType().getDisplayName(), (System.nanoTime() - startNanos) / 1_000_000,
                taskId, userId, StrUtil.blankToDefault(conversationId, "无"));
    }
}

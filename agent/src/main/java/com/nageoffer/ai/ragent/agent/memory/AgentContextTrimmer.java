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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话上下文裁剪：把过老的工具结果换成等长占位说明，不碰 IO 也不改列表长度
 * 只替换 tool_result 而不动 tool_use，因此永远不会产生孤儿结果块
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentContextTrimmer {

    /**
     * 回归台 AgentStateProbe 手抄了这个前缀，改这里必须同步
     */
    private static final String EVICTED_PREFIX = "[历史工具结果已省略，原长 ";
    private static final String EVICTED_CHARS = " 字符";
    private static final String EVICTED_INPUT = "，原入参 ";
    private static final String EVICTED_SUFFIX = "]";

    /**
     * 长入参截断，避免占位比原文还大
     */
    private static final int EVICTED_INPUT_MAX_CHARS = 120;

    private final AgentMemoryProperties memoryProperties;

    /**
     * 就地裁剪，返回替换映射供调用方同步上行列表
     */
    public TrimResult trimInPlace(List<Msg> context) {
        if (context == null || context.isEmpty()) {
            return TrimResult.UNCHANGED;
        }
        int totalChars = AgentContextChars.total(context);
        if (totalChars <= memoryProperties.resolveTrimTriggerChars()) {
            return TrimResult.UNCHANGED;
        }

        List<Cycle> cycles = splitCycles(context);
        Set<Integer> protectedCycles = protectedCycles(context, cycles, memoryProperties.resolveKeepRecentCycles());
        List<Candidate> candidates = collectCandidates(context, cycles, protectedCycles,
                memoryProperties.getEvictableTools());
        if (candidates.isEmpty()) {
            return TrimResult.UNCHANGED;
        }

        int reclaimable = candidates.stream().mapToInt(Candidate::reclaimable).sum();
        Map<Msg, Msg> replacements = apply(context, candidates);
        log.info("上下文裁剪完成, 总字符: {} -> {}, 命中消息: {}, 工具结果: {}",
                totalChars, totalChars - reclaimable, replacements.size(), candidates.size());
        return new TrimResult(reclaimable, replacements);
    }

    /**
     * 按工具循环切分：一条带 tool_use 的 assistant 消息开启一个循环，遇到用户消息或纯文本回答即闭合
     */
    private List<Cycle> splitCycles(List<Msg> context) {
        List<Cycle> cycles = new ArrayList<>();
        Cycle current = null;
        for (int i = 0; i < context.size(); i++) {
            Msg msg = context.get(i);
            MsgRole role = msg.getRole();
            if (role == MsgRole.TOOL) {
                if (current != null) {
                    current.toolIndexes().add(i);
                    for (ToolResultBlock block : blocks(msg, ToolResultBlock.class)) {
                        current.pendingIds().remove(block.getId());
                    }
                }
                continue;
            }
            List<ToolUseBlock> toolUses = blocks(msg, ToolUseBlock.class);
            if (role == MsgRole.ASSISTANT && !toolUses.isEmpty()) {
                current = new Cycle(i, new ArrayList<>(), new HashSet<>());
                for (ToolUseBlock block : toolUses) {
                    current.pendingIds().add(block.getId());
                }
                cycles.add(current);
                continue;
            }
            current = null;
        }
        return cycles;
    }

    /**
     * 本轮和未闭合的循环额外保护不占配额，keepRecentCycles 只在本轮之前计数
     */
    private Set<Integer> protectedCycles(List<Msg> context, List<Cycle> cycles, int keepRecentCycles) {
        int turnStart = lastUserIndex(context);
        Set<Integer> result = new HashSet<>();
        int kept = 0;
        for (int i = cycles.size() - 1; i >= 0; i--) {
            Cycle cycle = cycles.get(i);
            if (cycle.startIndex() > turnStart || !cycle.pendingIds().isEmpty()) {
                result.add(i);
                continue;
            }
            if (kept < keepRecentCycles) {
                result.add(i);
                kept++;
            }
        }
        return result;
    }

    /**
     * 取不到用户消息返回 -1，全部循环落保护区
     */
    private int lastUserIndex(List<Msg> context) {
        for (int i = context.size() - 1; i >= 0; i--) {
            if (context.get(i).getRole() == MsgRole.USER) {
                return i;
            }
        }
        return -1;
    }

    private List<Candidate> collectCandidates(List<Msg> context, List<Cycle> cycles,
                                              Set<Integer> protectedCycles, List<String> evictableTools) {
        List<Candidate> candidates = new ArrayList<>();
        for (int c = 0; c < cycles.size(); c++) {
            if (protectedCycles.contains(c)) {
                continue;
            }
            Cycle cycle = cycles.get(c);
            Map<String, String> inputs = toolInputs(context.get(cycle.startIndex()));
            for (int msgIndex : cycle.toolIndexes()) {
                for (ToolResultBlock block : blocks(context.get(msgIndex), ToolResultBlock.class)) {
                    // 工具名为空即框架级错误结果，跳过
                    if (block.getName() == null || !evictableTools.contains(block.getName()) || isEvicted(block)) {
                        continue;
                    }
                    String input = inputs.get(block.getId());
                    int originChars = AgentContextChars.ofOutput(block);
                    int reclaimable = originChars - previewChars(originChars, input);
                    if (reclaimable > 0) {
                        candidates.add(new Candidate(msgIndex, block, originChars, reclaimable, input));
                    }
                }
            }
        }
        return candidates;
    }

    /**
     * 按 tool_use id 配对取入参，整个打平不按工具名解析
     */
    private Map<String, String> toolInputs(Msg msg) {
        Map<String, String> inputs = new HashMap<>();
        for (ToolUseBlock block : blocks(msg, ToolUseBlock.class)) {
            inputs.put(block.getId(), clipInput(String.valueOf(block.getInput())));
        }
        return inputs;
    }

    private String clipInput(String input) {
        if (input == null || input.isBlank() || "null".equals(input)) {
            return null;
        }
        return input.length() <= EVICTED_INPUT_MAX_CHARS
                ? input
                : input.substring(0, EVICTED_INPUT_MAX_CHARS) + "…";
    }

    /**
     * 原位替换：先全部重建再统一 set，中途异常不改 context
     */
    private Map<Msg, Msg> apply(List<Msg> context, List<Candidate> candidates) {
        Map<ToolResultBlock, Candidate> hit = new IdentityHashMap<>();
        Set<Integer> touched = new HashSet<>();
        for (Candidate candidate : candidates) {
            hit.put(candidate.block(), candidate);
            touched.add(candidate.msgIndex());
        }
        Map<Integer, Msg> staged = new LinkedHashMap<>();
        Map<Msg, Msg> replacements = new IdentityHashMap<>();
        for (int msgIndex : touched) {
            Msg origin = context.get(msgIndex);
            List<ContentBlock> rebuilt = new ArrayList<>(origin.getContent().size());
            for (ContentBlock block : origin.getContent()) {
                Candidate candidate = block instanceof ToolResultBlock result ? hit.get(result) : null;
                rebuilt.add(candidate == null ? block : evict(candidate));
            }
            Msg replaced = origin.withContent(rebuilt);
            staged.put(msgIndex, replaced);
            replacements.put(origin, replaced);
        }
        staged.forEach(context::set);
        return replacements;
    }

    /**
     * 重建带全 id/name/metadata/state，漏 state 会把挂起/失败洗成默认值
     */
    private ToolResultBlock evict(Candidate candidate) {
        ToolResultBlock origin = candidate.block();
        return ToolResultBlock.builder()
                .id(origin.getId())
                .name(origin.getName())
                .output(TextBlock.builder().text(preview(candidate.originChars(), candidate.input())).build())
                .metadata(origin.getMetadata())
                .state(origin.getState())
                .build();
    }

    /**
     * 占位带原入参，让模型知道当时问的是什么
     */
    private String preview(int originChars, String input) {
        StringBuilder text = new StringBuilder(EVICTED_PREFIX).append(originChars).append(EVICTED_CHARS);
        if (input != null) {
            text.append(EVICTED_INPUT).append(input);
        }
        return text.append(EVICTED_SUFFIX).toString();
    }

    private int previewChars(int originChars, String input) {
        return preview(originChars, input).length();
    }

    /**
     * 靠占位前缀识别已清理块，不依赖 metadata
     * 对外开放供技能遮蔽复用：正文被换成占位后 metadata 仍在，判"已加载"必须再过这一道
     */
    public static boolean isEvicted(ToolResultBlock block) {
        List<ContentBlock> output = block.getOutput();
        return output != null && output.size() == 1
                && output.get(0) instanceof TextBlock text
                && text.getText() != null && text.getText().startsWith(EVICTED_PREFIX);
    }

    private <T extends ContentBlock> List<T> blocks(Msg msg, Class<T> type) {
        return msg.getContent() == null ? List.of() : msg.getContentBlocks(type);
    }

    /**
     * 替换映射按引用比对，供调用方把同一批 Msg 换进本轮上行列表
     */
    public record TrimResult(int reclaimedChars, Map<Msg, Msg> replacements) {

        public static final TrimResult UNCHANGED = new TrimResult(0, Map.of());

        public boolean changed() {
            return reclaimedChars > 0 && !replacements.isEmpty();
        }
    }

    private record Cycle(int startIndex, List<Integer> toolIndexes, Set<String> pendingIds) {
    }

    private record Candidate(int msgIndex, ToolResultBlock block, int originChars, int reclaimable, String input) {
    }
}

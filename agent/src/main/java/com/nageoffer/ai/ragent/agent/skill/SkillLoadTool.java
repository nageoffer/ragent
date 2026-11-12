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

package com.nageoffer.ai.ragent.agent.skill;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.trace.AgentToolBodyTracer;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkill;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 技能加载工具：渐进式披露的入口，技能清单挂在本工具的 description 上，正文由模型判断命中后再取
 */
@RequiredArgsConstructor
public class SkillLoadTool implements AgentTool {

    public static final String TOOL_NAME = "load_skill";
    public static final String DISPLAY_NAME = "加载技能手册";

    /**
     * 加载成功的标记写在结果 metadata 上，遮蔽中间件据此放行该技能解锁的工具
     */
    public static final String LOADED_SKILL_METADATA_KEY = "ragent_loaded_skill";

    private static final String SKILL_CODE_PARAM = "skill_code";

    private static final String DESCRIPTION_HEADER = """
            加载指定技能的流程手册，获取该类任务的处理步骤与注意事项。
            当前任务符合下方某项技能的适用条件时，先加载对应手册，再按手册处理。
            部分业务工具在关联手册加载后才会出现在可用工具中，不要仅凭当前工具清单判断是否支持该任务。
            上下文里还能看到所需手册的完整正文就直接照做，不重复加载；看不到了再加载，摘要里提到已加载不算。

            可用技能：""";

    private final AgentSkillRegistry skillRegistry;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        String manifest = skillRegistry.listEnabled().stream()
                .map(skill -> "- %s（%s）：%s".formatted(
                        skill.skillCode(), skill.name(), StrUtil.emptyIfNull(skill.description())))
                .collect(Collectors.joining("\n"));
        return DESCRIPTION_HEADER + "\n" + manifest;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> skillCode = new LinkedHashMap<>();
        skillCode.put("type", "string");
        skillCode.put("description", "技能标识，取值见本工具说明里的可用技能清单");
        List<String> codes = skillRegistry.listEnabled().stream().map(AgentSkill::skillCode).toList();
        if (!codes.isEmpty()) {
            skillCode.put("enum", codes);
        }
        return Map.of(
                "type", "object",
                "properties", Map.of(SKILL_CODE_PARAM, skillCode),
                "required", List.of(SKILL_CODE_PARAM),
                "additionalProperties", false);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return AgentToolBodyTracer.trace(this, param, () -> Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 正文调用时才从注册表取，改手册无需重建 Agent
     */
    private ToolResultBlock execute(ToolCallParam param) {
        if (param == null) {
            return failure(null, "工具调用参数不能为空");
        }
        String toolCallId = param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId();
        Object raw = param.getInput() == null ? null : param.getInput().get(SKILL_CODE_PARAM);
        String skillCode = raw instanceof String value ? value.trim() : null;
        if (StrUtil.isBlank(skillCode)) {
            return failure(toolCallId, "工具参数 skill_code 不能为空");
        }
        AgentSkill skill = skillRegistry.findByCode(skillCode);
        if (skill == null) {
            return failure(toolCallId, "技能不存在或已停用：" + skillCode + "，可用技能见本工具说明里的清单");
        }
        return success(toolCallId, skill);
    }

    private ToolResultBlock success(String toolCallId, AgentSkill skill) {
        String text = "已加载技能「" + skill.name() + "」。以下是这类任务的处理手册，接下来按它执行：\n\n"
                + StrUtil.emptyIfNull(skill.content());
        return block(toolCallId, text,
                Map.of(LOADED_SKILL_METADATA_KEY, skill.skillCode()), ToolResultState.SUCCESS);
    }

    private ToolResultBlock failure(String toolCallId, String text) {
        return block(toolCallId, text, Map.of(), ToolResultState.ERROR);
    }

    private ToolResultBlock block(String toolCallId, String text, Map<String, Object> metadata,
                                  ToolResultState state) {
        List<ContentBlock> output = List.of(TextBlock.builder().text(text).build());
        return new ToolResultBlock(toolCallId, TOOL_NAME, output, metadata, state);
    }
}

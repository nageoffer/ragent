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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mzt.logapi.starter.annotation.LogRecord;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.OrchestrationMode;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.controller.request.AgentProfileSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.request.AgentPromptSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentProfileListVO;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentProfileVO;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentPromptConfigVO;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptCacheManager;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileDO;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentPromptDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentPromptMapper;
import com.nageoffer.ai.ragent.rag.service.AgentProfileAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentProfileAdminServiceImpl implements AgentProfileAdminService {

    /**
     * 与 t_agent_profile.avatar 列宽一致
     */
    private static final int AVATAR_MAX_LENGTH = 32;

    private final AgentProfileMapper agentProfileMapper;
    private final AgentPromptMapper agentPromptMapper;
    private final AgentPromptResolver agentPromptResolver;
    private final AgentPromptCacheManager cacheManager;
    private final OrchestrationProperties orchestrationProperties;
    private final BizChangeLogContext bizChangeLogContext;

    @Override
    public AgentProfileListVO list() {
        OrchestrationMode mode = orchestrationProperties.getMode();
        Map<String, List<AgentPromptSlot>> configuredSlots = loadConfiguredSlots();

        List<AgentProfileVO> agents = agentProfileMapper.selectList(Wrappers.lambdaQuery(AgentProfileDO.class))
                .stream()
                .sorted(Comparator.comparing((AgentProfileDO each) -> !isTrue(each.getBuiltin()))
                        .thenComparing(AgentProfileDO::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(each -> {
                    List<AgentPromptSlot> own = configuredSlots.getOrDefault(each.getId(), List.of());
                    int effective = (int) own.stream().filter(slot -> slot.isEffectiveIn(mode)).count();
                    return AgentProfileVO.builder()
                            .id(each.getId())
                            .name(each.getName())
                            .description(each.getDescription())
                            .avatar(each.getAvatar())
                            .builtin(isTrue(each.getBuiltin()))
                            .active(isTrue(each.getActive()))
                            .effectiveSlots(effective)
                            .inactiveSlots(own.size() - effective)
                            .createTime(each.getCreateTime())
                            .updateTime(each.getUpdateTime())
                            .build();
                })
                .toList();
        return AgentProfileListVO.builder()
                .mode(mode.name())
                .effectiveSlotTotal(AgentPromptSlot.effectiveIn(mode).size())
                .agents(agents)
                .build();
    }

    /**
     * 按智能体聚合已配置的槽位，只投影两个键以免把大段提示词正文全捞回内存
     * <p>
     * isNotNull + ne("") 即视为已配置，与 {@code AgentPromptResolver#putNonBlank} 的
     * {@code StrUtil.isNotBlank} 判定一致：savePrompt 已将空白内容归一化为 null，因此
     * 正常路径不会出现纯空白行，无需为防御性 trim 而把大段 content 捞回内存
     */
    private Map<String, List<AgentPromptSlot>> loadConfiguredSlots() {
        List<AgentPromptDO> prompts = agentPromptMapper.selectList(Wrappers.lambdaQuery(AgentPromptDO.class)
                .select(AgentPromptDO::getAgentId, AgentPromptDO::getSlotKey)
                .isNotNull(AgentPromptDO::getContent)
                .ne(AgentPromptDO::getContent, ""));

        Map<String, List<AgentPromptSlot>> configured = new HashMap<>();
        for (AgentPromptDO prompt : prompts) {
            // 认不出的 slot_key 是删槽位留下的历史数据，读不到也统计不进任何一边
            AgentPromptSlot.find(prompt.getSlotKey()).ifPresent(slot ->
                    configured.computeIfAbsent(prompt.getAgentId(), key -> new ArrayList<>()).add(slot));
        }
        return configured;
    }

    @Override
    @LogRecord(
            success = "创建智能体：{{#requestParam.name}}",
            fail = "创建智能体失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_PROFILE,
            subType = BizChangeOperationType.CREATE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public String create(AgentProfileSaveRequest requestParam) {
        String name = trimmedName(requestParam);
        assertNameAvailable(name, null);

        AgentProfileDO profile = AgentProfileDO.builder()
                .name(name)
                .description(StrUtil.trimToNull(requestParam.getDescription()))
                .avatar(trimmedAvatar(requestParam))
                .builtin(0)
                .active(0)
                .build();
        agentProfileMapper.insert(profile);
        bizChangeLogContext.put(profile.getId(), null, profile);
        return profile.getId();
    }

    @Override
    @LogRecord(
            success = "更新智能体：{{#id}}",
            fail = "更新智能体失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_PROFILE,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void update(String id, AgentProfileSaveRequest requestParam) {
        AgentProfileDO before = mustLoadEditable(id);
        String name = trimmedName(requestParam);
        assertNameAvailable(name, id);

        // 用显式 set 而非 updateById：默认 NOT_NULL 策略会跳过 null，描述就清不掉
        agentProfileMapper.update(Wrappers.lambdaUpdate(AgentProfileDO.class)
                .set(AgentProfileDO::getName, name)
                .set(AgentProfileDO::getDescription, StrUtil.trimToNull(requestParam.getDescription()))
                .set(AgentProfileDO::getAvatar, trimmedAvatar(requestParam))
                .eq(AgentProfileDO::getId, id));
        bizChangeLogContext.put(id, before, agentProfileMapper.selectById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "删除智能体：{{#id}}",
            fail = "删除智能体失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_PROFILE,
            subType = BizChangeOperationType.DELETE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION
    )
    public void delete(String id) {
        AgentProfileDO profile = mustLoadEditable(id);
        if (isTrue(profile.getActive())) {
            throw new ClientException("该智能体正在激活中，请先激活其他智能体再删除");
        }

        agentPromptMapper.delete(Wrappers.lambdaQuery(AgentPromptDO.class).eq(AgentPromptDO::getAgentId, id));
        agentProfileMapper.deleteById(id);
        bizChangeLogContext.put(id, profile, null);
        cacheManager.clearCache();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "激活智能体：{{#id}}",
            fail = "激活智能体失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_PROFILE,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#id}}"
    )
    public void activate(String id) {
        AgentProfileDO target = mustLoad(id);

        // 显式 set 清零，不依赖 Builder + update(entity, wrapper) 的 NOT_NULL 策略
        agentProfileMapper.update(null,
                Wrappers.lambdaUpdate(AgentProfileDO.class)
                        .set(AgentProfileDO::getActive, 0)
                        .eq(AgentProfileDO::getActive, 1));

        target.setActive(1);
        agentProfileMapper.updateById(target);
        cacheManager.clearCache();
    }

    @Override
    public AgentPromptConfigVO loadPrompts(String id) {
        AgentProfileDO profile = mustLoad(id);
        AgentProfileDO builtin = loadBuiltin();
        OrchestrationMode mode = orchestrationProperties.getMode();
        Map<String, String> own = agentPromptResolver.loadOwnPrompts(id);

        List<AgentPromptConfigVO.Slot> slots = Arrays.stream(AgentPromptSlot.values())
                .map(slot -> AgentPromptConfigVO.Slot.builder()
                        .slotKey(slot.name())
                        .displayName(slot.getDisplayName())
                        .group(slot.getGroup().name())
                        .groupName(slot.getGroup().getDisplayName())
                        .effective(slot.isEffectiveIn(mode))
                        .inactiveReason(slot.isEffectiveIn(mode) ? null : slot.getInactiveReason())
                        .requiredPlaceholders(slot.getRequiredPlaceholders())
                        .content(StrUtil.emptyIfNull(own.get(slot.name())))
                        .build())
                .toList();

        return AgentPromptConfigVO.builder()
                .agentId(id)
                .agentName(profile.getName())
                .builtin(isTrue(profile.getBuiltin()))
                .defaultAgentName(builtin == null ? null : builtin.getName())
                .mode(mode.name())
                .slots(slots)
                .build();
    }

    @Override
    @LogRecord(
            success = "更新智能体提示词：{{#id}} / {{#slotKey}}",
            fail = "更新智能体提示词失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_PROFILE,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#id}}"
    )
    public void savePrompt(String id, String slotKey, AgentPromptSaveRequest requestParam) {
        mustLoadEditable(id);
        AgentPromptSlot slot = AgentPromptSlot.find(slotKey)
                .orElseThrow(() -> new ClientException("未知的提示词：" + slotKey));

        String content = requestParam == null ? null : requestParam.getContent();
        assertPlaceholdersPresent(slot, content);

        boolean existed = agentPromptMapper.exists(Wrappers.lambdaQuery(AgentPromptDO.class)
                .eq(AgentPromptDO::getAgentId, id)
                .eq(AgentPromptDO::getSlotKey, slot.name()));
        if (existed) {
            // 清空内容必须真写 null 才能恢复回落
            agentPromptMapper.update(Wrappers.lambdaUpdate(AgentPromptDO.class)
                    .set(AgentPromptDO::getContent, StrUtil.isBlank(content) ? null : content)
                    .eq(AgentPromptDO::getAgentId, id)
                    .eq(AgentPromptDO::getSlotKey, slot.name()));
        } else if (StrUtil.isNotBlank(content)) {
            agentPromptMapper.insert(AgentPromptDO.builder()
                    .agentId(id)
                    .slotKey(slot.name())
                    .content(content)
                    .build());
        }
        cacheManager.clearCache();
    }

    @Override
    public String defaultPrompt(String slotKey) {
        AgentPromptSlot slot = AgentPromptSlot.find(slotKey)
                .orElseThrow(() -> new ClientException("未知的提示词：" + slotKey));
        AgentProfileDO builtin = loadBuiltin();
        if (builtin == null) {
            return "";
        }
        return StrUtil.emptyIfNull(agentPromptResolver.loadOwnPrompts(builtin.getId()).get(slot.name()));
    }

    /**
     * 内置智能体，缺失时返回 null 由调用方降级
     */
    private AgentProfileDO loadBuiltin() {
        return agentProfileMapper.selectOne(Wrappers.lambdaQuery(AgentProfileDO.class)
                .eq(AgentProfileDO::getBuiltin, 1));
    }

    /**
     * 占位符缺失会让下游规则静默失效，症状难以归因，故在保存时就拒绝
     */
    private void assertPlaceholdersPresent(AgentPromptSlot slot, String content) {
        if (StrUtil.isBlank(content) || slot.getRequiredPlaceholders().isEmpty()) {
            return;
        }
        List<String> missing = slot.getRequiredPlaceholders().stream()
                .filter(placeholder -> !content.contains(placeholder))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new ClientException(StrUtil.format("「{}」缺少必需占位符：{}", slot.getDisplayName(),
                    String.join("、", missing)));
        }
    }

    private String trimmedName(AgentProfileSaveRequest requestParam) {
        String name = requestParam == null ? null : StrUtil.trim(requestParam.getName());
        if (StrUtil.isBlank(name)) {
            throw new ClientException("智能体名称不能为空");
        }
        return name;
    }

    /**
     * 头像预设表在前端，这里只做长度校验：认不出的取值前端会按 id 哈希兜底，不至于渲染成空白
     */
    private String trimmedAvatar(AgentProfileSaveRequest requestParam) {
        String avatar = requestParam == null ? null : StrUtil.trimToNull(requestParam.getAvatar());
        if (avatar != null && avatar.length() > AVATAR_MAX_LENGTH) {
            throw new ClientException("头像标识过长");
        }
        return avatar;
    }

    private void assertNameAvailable(String name, String excludeId) {
        boolean duplicated = agentProfileMapper.exists(Wrappers.lambdaQuery(AgentProfileDO.class)
                .eq(AgentProfileDO::getName, name)
                .ne(StrUtil.isNotBlank(excludeId), AgentProfileDO::getId, excludeId));
        if (duplicated) {
            throw new ClientException("智能体名称已存在");
        }
    }

    private AgentProfileDO mustLoad(String id) {
        AgentProfileDO profile = StrUtil.isBlank(id) ? null : agentProfileMapper.selectById(id);
        if (profile == null) {
            throw new ClientException("智能体不存在");
        }
        return profile;
    }

    private AgentProfileDO mustLoadEditable(String id) {
        AgentProfileDO profile = mustLoad(id);
        if (isTrue(profile.getBuiltin())) {
            throw new ClientException("内置智能体不可编辑或删除，如需调整请复制一份新建");
        }
        return profile;
    }

    private static boolean isTrue(Integer flag) {
        return flag != null && flag == 1;
    }
}

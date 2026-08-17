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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.controller.request.AgentProfileSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.request.AgentPromptSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentProfileListVO;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentPromptConfigVO;
import com.nageoffer.ai.ragent.rag.service.AgentProfileAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能体管理控制器
 */
@RestController
@RequiredArgsConstructor
public class AgentProfileController {

    private final AgentProfileAdminService agentProfileAdminService;

    /**
     * 查询智能体列表
     */
    @GetMapping("/agents")
    public Result<AgentProfileListVO> list() {
        return Results.success(agentProfileAdminService.list());
    }

    /**
     * 创建智能体
     */
    @PostMapping("/agents")
    public Result<String> create(@RequestBody AgentProfileSaveRequest requestParam) {
        return Results.success(agentProfileAdminService.create(requestParam));
    }

    /**
     * 更新智能体名称与描述
     */
    @PutMapping("/agents/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody AgentProfileSaveRequest requestParam) {
        agentProfileAdminService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除智能体
     */
    @DeleteMapping("/agents/{id}")
    public Result<Void> delete(@PathVariable String id) {
        agentProfileAdminService.delete(id);
        return Results.success();
    }

    /**
     * 激活智能体，立即对全部会话生效
     */
    @PostMapping("/agents/{id}/activate")
    public Result<Void> activate(@PathVariable String id) {
        agentProfileAdminService.activate(id);
        return Results.success();
    }

    /**
     * 查询该智能体的槽位配置，含槽位元数据与当前架构下的生效判定
     */
    @GetMapping("/agents/{id}/prompts")
    public Result<AgentPromptConfigVO> prompts(@PathVariable String id) {
        return Results.success(agentProfileAdminService.loadPrompts(id));
    }

    /**
     * 保存单个槽位，内容留空即恢复回落内置智能体
     */
    @PutMapping("/agents/{id}/prompts/{slotKey}")
    public Result<Void> savePrompt(@PathVariable String id,
                                   @PathVariable String slotKey,
                                   @RequestBody AgentPromptSaveRequest requestParam) {
        agentProfileAdminService.savePrompt(id, slotKey, requestParam);
        return Results.success();
    }

    /**
     * 查询内置智能体的槽位内容，供「从默认复制」
     */
    @GetMapping("/agents/prompt-slots/{slotKey}/default")
    public Result<String> defaultPrompt(@PathVariable String slotKey) {
        return Results.success(agentProfileAdminService.defaultPrompt(slotKey));
    }
}

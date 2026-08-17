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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.controller.request.AgentProfileSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.request.AgentPromptSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentProfileListVO;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentPromptConfigVO;

public interface AgentProfileAdminService {

    /**
     * 查询全部智能体，内置在前、其余按创建时间
     */
    AgentProfileListVO list();

    String create(AgentProfileSaveRequest requestParam);

    void update(String id, AgentProfileSaveRequest requestParam);

    void delete(String id);

    /**
     * 激活指定智能体，全局仅保留一条激活态
     */
    void activate(String id);

    /**
     * 查询该智能体的全部槽位配置，含元数据与当前架构下的生效判定
     */
    AgentPromptConfigVO loadPrompts(String id);

    /**
     * 保存单个槽位，内容留空即恢复回落内置智能体
     */
    void savePrompt(String id, String slotKey, AgentPromptSaveRequest requestParam);

    /**
     * 取内置智能体的该槽位内容，供控制台「从默认复制」
     */
    String defaultPrompt(String slotKey);
}

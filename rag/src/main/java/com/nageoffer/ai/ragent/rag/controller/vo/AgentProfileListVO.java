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

package com.nageoffer.ai.ragent.rag.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 智能体列表，附带当前执行架构供页面标注哪些槽位生效
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentProfileListVO {

    /**
     * 取自 ragent.engine.type，部署级配置，页面只读展示
     */
    private String mode;

    /**
     * 当前架构下生效的槽位总数，全体智能体共用，作覆盖率分母
     */
    private Integer effectiveSlotTotal;

    private List<AgentProfileVO> agents;
}

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

import java.util.Date;

/**
 * 智能体视图对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentProfileVO {

    private String id;
    private String name;
    private String description;

    /**
     * 头像预设标识
     */
    private String avatar;

    /**
     * 内置智能体不可编辑不可删除
     */
    private Boolean builtin;

    private Boolean active;

    /**
     * 自身已填写、且在当前架构下会被读取的槽位数，其余槽位回落内置
     */
    private Integer effectiveSlots;

    /**
     * 已填写但当前架构读不到的槽位数，切换 ragent.engine.type 后才会生效
     */
    private Integer inactiveSlots;

    private Date createTime;
    private Date updateTime;
}

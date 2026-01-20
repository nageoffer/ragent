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

package com.nageoffer.ai.ragent.service.guidance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 引导式问答状态实体类
 * 用于记录用户在引导式问答过程中的当前状态和相关信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuidanceState {

    /**
     * 用户原始问题
     */
    private String originalQuestion;

    /**
     * 当前聚焦的主题名称（如：数据安全）
     */
    private String topicName;

    /**
     * 当前引导式问答所在节点
     */
    private String currentNodeId;

    /**
     * 候选叶子节点
     */
    private List<String> candidateLeafIds;

    /**
     * 当前提示选项（节点 ID 列表，顺序即编号顺序）
     */
    private List<String> optionIds;
}

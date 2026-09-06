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

package com.nageoffer.ai.ragent.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 运行轨迹块：按事件顺序排列的 reasoning / answer / tool / confirm / error 片段，随消息落库
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentBlock {

    /**
     * reasoning / answer / tool / confirm / error
     */
    private String kind;

    /**
     * 产生时刻 yyyy-MM-dd'T'HH:mm:ss，历史数据是 HH:mm:ss，前端两种都认
     */
    private String at;

    /**
     * reasoning / answer 的正文
     */
    private String text;

    /**
     * tool 名
     */
    private String name;

    /**
     * tool 展示名
     */
    private String displayName;

    /**
     * tool 终态 done / interrupted，confirm 终态 pending / approved / denied
     */
    private String status;

    /**
     * tool 结果文本，超长截断
     */
    private String result;

    /**
     * 供应商侧的 tool_call id，与上下文里 tool_use / tool_result 同源；端点不回时留空
     */
    private String toolCallId;

    /**
     * confirm 块待用户裁决的工具调用，整卡一次决策，不逐条勾选
     */
    private List<AgentConfirmCall> calls;
}

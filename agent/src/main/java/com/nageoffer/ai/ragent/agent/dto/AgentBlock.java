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
     * durationSource 值：耗时量的是这条工具自己的执行体
     */
    public static final String DURATION_SOURCE_TOOL = "tool";

    /**
     * kind 取值，产出方与读取方共用这一套字面量
     */
    public static final String KIND_ANSWER = "answer";
    public static final String KIND_REASONING = "reasoning";
    public static final String KIND_ERROR = "error";
    public static final String KIND_TOOL = "tool";
    public static final String KIND_CONFIRM = "confirm";

    /**
     * reasoning / answer / tool / confirm / error
     */
    private String kind;

    /**
     * 块产生时刻 yyyy-MM-dd'T'HH:mm:ss（历史 HH:mm:ss，前端两种都认）
     * 工具块是模型开始吐参数的时刻，不是执行起点，量耗时看 startedAt
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
     * tool 调用状态 pending / running / awaiting / done / failed / denied / interrupted；
     * confirm 用户决定 pending / approved / denied，expired 表示失效；approved 不代表工具执行成功
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
     * 同批工具共享的批次号，未执行的块没有批
     */
    private String batchId;

    /**
     * 组内序号（0 基），同名并行调用靠它区分先后
     */
    private Integer callIndex;

    /**
     * 起点 epoch millis（服务端时刻）
     * 工具块：进入工具体，不含模型吐参数阶段；文本块：首个增量到达
     */
    private Long startedAt;

    /**
     * 终点 epoch millis（服务端时刻），断在半路留空
     */
    private Long endedAt;

    /**
     * 耗时，两端齐了才有
     */
    private Long durationMs;

    /**
     * 耗时口径 tool / batch，缺省即 batch（老数据整批共享）；文本块留空
     */
    private String durationSource;

    /**
     * confirm 块待用户裁决的工具调用，整卡一次决策，不逐条勾选
     */
    private List<AgentConfirmCall> calls;
}

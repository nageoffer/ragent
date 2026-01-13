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

package com.nageoffer.ai.ragent.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Builder.Default;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用大模型请求对象
 *
 * <p>
 * 用于封装一次完整对话所需的所有上下文与控制参数，作为「统一入参」传给
 * 各种不同厂商 / 协议的大模型接口（如 Ollama、百炼、OpenAI 等），
 * 方便在适配层做统一转换
 * </p>
 *
 * <p>典型使用方式：</p>
 * <pre>
 * ChatRequest req = ChatRequest.builder()
 *     .prompt("帮我总结下这段文本")
 *     .systemPrompt("你是一个企业内部知识库助手")
 *     .context(ragContext)          // 可选：RAG 召回的文档内容
 *     .temperature(0.3)
 *     .maxTokens(512)
 *     .build();
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatRequest {

    /**
     * 完整消息列表
     * <p>
     * 用于直接传入 system/user/assistant 消息序列。
     * 当 messages 非空时，适配层使用该字段构造请求；
     * prompt 会作为额外的 user 消息追加。
     * </p>
     */
    @Default
    private List<ChatMessage> messages = new ArrayList<>();

    // ================== 模型控制参数 ==================

    /**
     * 采样温度参数，取值通常为 0～2
     * <p>
     * 数值越小，输出越稳定、保守；数值越大，生成内容越发散、创造性更强
     * 例如：问答场景可用 0.1～0.5，创作类可用 0.7 以上
     * </p>
     */
    private Double temperature;

    /**
     * nucleus sampling（Top-P）参数
     * <p>
     * 表示从累积概率为 P 的词集合中采样，常与 {@link #temperature} 搭配使用
     * 一般取值在 0.8～0.95 之间，越小越保守
     * 若为 {@code null} 则使用模型默认值
     * </p>
     */
    private Double topP;

    /**
     * 限制模型本次回答最多生成的 token 数量
     * <p>
     * 可用于控制回复长度与成本；若为 {@code null}，则走模型或服务端默认配置
     * </p>
     */
    private Integer maxTokens;

    /**
     * 可选：是否启用「思考模式」开关
     * <p>
     * 占坑字段，用于兼容支持思考过程 / reasoning 扩展能力的模型，
     * 具体含义由对接的大模型服务决定（例如是否返回中间推理过程等）
     * 不支持该能力的实现可以忽略该字段
     * </p>
     */
    private Boolean thinking;

    /**
     * 可选：是否启用工具调用（Tool Calling / Function Calling）
     * <p>
     * 当前预留字段，方便后续扩展为带工具调用能力的对话请求：
     * <ul>
     *   <li>{@code false}：只进行纯文本对话</li>
     *   <li>{@code true}：允许模型按照定义调用工具 / 函数</li>
     * </ul>
     * 具体工具列表、调用结果处理由上层或实现层定义
     * </p>
     */
    private Boolean enableTools;
}

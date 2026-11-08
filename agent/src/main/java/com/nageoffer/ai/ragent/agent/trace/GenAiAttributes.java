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

package com.nageoffer.ai.ragent.agent.trace;

import io.opentelemetry.api.common.AttributeKey;

/**
 * GenAI 语义约定里我方自己写出的键，全部集中在这里
 * <p>
 * 坐标：open-telemetry/semantic-conventions-genai 仓的 docs/gen-ai/gen-ai-spans.md（主 semconv 仓那份已迁走只剩指针）
 * 这批键仍处于 Development 阶段，上游改名时只动本类，别在业务代码里散写字符串
 */
public final class GenAiAttributes {

    /**
     * 工具调用 ID，与 PG 块、SSE 帧里的 toolCallId 同源
     */
    public static final AttributeKey<String> TOOL_CALL_ID = AttributeKey.stringKey("gen_ai.tool.call.id");

    public static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("gen_ai.tool.name");

    /**
     * 操作名，execute_tool 一行在规范里是必填；后端靠它认出这是一次工具执行
     */
    public static final AttributeKey<String> OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");

    public static final AttributeKey<String> TOOL_DESCRIPTION = AttributeKey.stringKey("gen_ai.tool.description");

    /**
     * 规范给的三档是 function / extension / datastore，后两档指模型服务商自己托管的工具
     * 我方四把工具都是本进程按 schema 描述的函数，一律 function
     */
    public static final AttributeKey<String> TOOL_TYPE = AttributeKey.stringKey("gen_ai.tool.type");

    /**
     * 模型服务商，规范里是 chat span 的必填项；按客户端协议口径取值，网关转发到哪家不影响这里
     */
    public static final AttributeKey<String> PROVIDER_NAME = AttributeKey.stringKey("gen_ai.provider.name");

    /**
     * 模型名，官方 chat span 写的也是这把键；摘要节点不经官方那层，只能自己写
     */
    public static final AttributeKey<String> REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");

    /**
     * 是否流式，规范脚注写明「未设即视为非流式」，我方全程流式，漏写就是把语义说反
     */
    public static final AttributeKey<Boolean> REQUEST_STREAM = AttributeKey.booleanKey("gen_ai.request.stream");

    /**
     * 工具出入参属于可能包含业务数据的内容字段，故一律受 capture-content 管；产品默认开启但允许显式关闭
     * 结构化 any 值 OTel Java 尚不支持，落成统一序列化的 JSON 串
     */
    public static final AttributeKey<String> TOOL_CALL_ARGUMENTS =
            AttributeKey.stringKey("gen_ai.tool.call.arguments");

    public static final AttributeKey<String> TOOL_CALL_RESULT = AttributeKey.stringKey("gen_ai.tool.call.result");

    public static final String OPERATION_EXECUTE_TOOL = "execute_tool";
    public static final String TOOL_TYPE_FUNCTION = "function";

    /**
     * 本仓统一走 OpenAI 协议的客户端，即使 base-url 指向兼容网关也按协议口径记 openai
     */
    public static final String PROVIDER_OPENAI = "openai";

    private GenAiAttributes() {
    }
}

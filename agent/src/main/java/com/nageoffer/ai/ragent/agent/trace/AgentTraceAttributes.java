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
 * LangFuse 的 OTel 属性名，集中一处便于对照官方文档
 * langfuse.* 优先级高于通用 gen_ai.*，官方中间件已写的那几个 gen_ai 属性不在此重复
 */
final class AgentTraceAttributes {

    /**
     * 整条 trace 的名字，缺省会退化成根 span 名
     */
    static final AttributeKey<String> TRACE_NAME = AttributeKey.stringKey("langfuse.trace.name");

    /**
     * 用户与会话：官方文档要求写在每个 span 上，只写根 span 会让 observation 级过滤失准
     */
    static final AttributeKey<String> USER_ID = AttributeKey.stringKey("langfuse.user.id");
    static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("langfuse.session.id");

    static final AttributeKey<String> OBSERVATION_TYPE = AttributeKey.stringKey("langfuse.observation.type");
    static final AttributeKey<String> OBSERVATION_INPUT = AttributeKey.stringKey("langfuse.observation.input");
    static final AttributeKey<String> OBSERVATION_OUTPUT = AttributeKey.stringKey("langfuse.observation.output");
    static final AttributeKey<String> MODEL_PARAMETERS = AttributeKey.stringKey("langfuse.observation.model.parameters");
    static final AttributeKey<String> USAGE_DETAILS = AttributeKey.stringKey("langfuse.observation.usage_details");
    static final AttributeKey<String> LEVEL = AttributeKey.stringKey("langfuse.observation.level");
    static final AttributeKey<String> STATUS_MESSAGE = AttributeKey.stringKey("langfuse.observation.status_message");

    /**
     * 首个 token 落地时刻，ISO-8601；LangFuse 用它算首字延迟
     */
    static final AttributeKey<String> COMPLETION_START_TIME =
            AttributeKey.stringKey("langfuse.observation.completion_start_time");

    /**
     * 工具清单的渲染载荷，几十份 schema 拼成的长串，只供本页展开看
     * <p>
     * 可过滤、可聚合的那些运行事实一律走 ragent.*，这里只留「换个后端就没人渲染」的整块正文
     */
    static final AttributeKey<String> META_TOOL_DEFINITIONS =
            AttributeKey.stringKey("langfuse.observation.metadata.tool_definitions");

    /**
     * observation 类型取值，非法值会被 LangFuse 忽略后按其它规则回退
     * 工具批不用 tool：那一个节点上可能挂着并发执行的好几个工具，tool 在 LangFuse 里指单次调用
     * tool 只留给工具执行体那一层，一次执行只该有一个带 tool 语义的节点
     */
    static final String TYPE_AGENT = "agent";
    static final String TYPE_CHAIN = "chain";
    static final String TYPE_GENERATION = "generation";
    static final String TYPE_GUARDRAIL = "guardrail";
    static final String TYPE_SPAN = "span";
    static final String TYPE_TOOL = "tool";

    static final String LEVEL_WARNING = "WARNING";
    static final String LEVEL_ERROR = "ERROR";

    private AgentTraceAttributes() {
    }
}

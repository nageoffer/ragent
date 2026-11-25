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
 * 本项目自有的追踪属性，与标准区 gen_ai.* 和厂商区 langfuse.* 三层互不越界
 * <p>
 * 这一层表达的是标准约定还没有的项目事实，换任何 OTLP 后端都仍然成立
 */
public final class RagentAttributes {

    /**
     * 整轮运行的结局，回答「这条 trace 是怎么结束的」，与工具级、模型级的成败无关
     */
    public static final AttributeKey<String> RUN_OUTCOME = AttributeKey.stringKey("ragent.run.outcome");

    public static final String RUN_OUTCOME_COMPLETED = "completed";
    public static final String RUN_OUTCOME_FAILED = "failed";

    /**
     * 用户点了停止，框架在窗口内自行收了尾，已产出的内容都存了盘
     */
    public static final String RUN_OUTCOME_INTERRUPTED = "interrupted";

    /**
     * 等不到框架收尾，链路被直接掐断，这一轮多半没存盘
     */
    public static final String RUN_OUTCOME_ABORTED = "aborted";

    /**
     * 一轮推理自己的结局，取值与运行结局同一套；span 状态只有 OK/ERROR 两档，分不出被停掉和真报错
     */
    public static final AttributeKey<String> ROUND_OUTCOME = AttributeKey.stringKey("ragent.round.outcome");

    /**
     * 本轮在 ReAct 里的序号，从 1 起；推理、模型调用、工具批、工具执行体四类节点同值
     * <p>
     * 推理与工具在树上是兄弟（框架没有覆盖「一轮」的切面），同一轮只能靠这把键认出来
     */
    public static final AttributeKey<Long> REACT_ROUND_INDEX = AttributeKey.longKey("ragent.react.round_index");

    /**
     * 整次运行跑了几轮，只写根 span；与轮次序号是两把键，混用会把「第 2 轮」读成「共 2 轮」
     */
    public static final AttributeKey<Long> REACT_ROUNDS = AttributeKey.longKey("ragent.react.rounds");

    /**
     * 本轮运行的触发方式，续跑与首问的链路形状不同，聚合时得分开看
     */
    public static final AttributeKey<String> RUN_KIND = AttributeKey.stringKey("ragent.run.kind");

    public static final String RUN_KIND_ASK = "ask";
    public static final String RUN_KIND_CONFIRM_RESUME = "confirm_resume";

    /**
     * trace 名前缀，拼上运行方式即全名；命名空间与 RUN_KIND 那把键对齐，看见名字就知道该按哪把键过滤
     * <p>
     * 名字是后端的聚合维度，与 span 名、属性取值同属机器可读的那一层，故一律 ASCII 小写，不带空格与中文
     */
    public static final String TRACE_NAME_PREFIX = "ragent.run.";

    /**
     * 业务 ID：PG 那侧的主键，链路里带上它们才有从消息记录反查到本条 trace 的路
     */
    public static final AttributeKey<String> TASK_ID = AttributeKey.stringKey("ragent.task.id");
    public static final AttributeKey<String> REPLY_TO_MESSAGE_ID = AttributeKey.stringKey("ragent.reply_to.message_id");
    public static final AttributeKey<String> CONFIRM_MESSAGE_ID = AttributeKey.stringKey("ragent.confirm.message_id");

    /**
     * 入口审核方式，取值同 ragent.input-guard.type 的写法
     */
    public static final AttributeKey<String> INPUT_GUARD_TYPE = AttributeKey.stringKey("ragent.input_guard.type");

    /**
     * 遮蔽生效之后模型真能看见的工具名单，能直接看出技能遮蔽有没有在干活
     */
    public static final AttributeKey<String> TOOLS_VISIBLE = AttributeKey.stringKey("ragent.tools.visible");

    /**
     * 进本轮推理的消息条数与字符数，上下文压缩与裁剪的效果全看这两个数
     */
    public static final AttributeKey<Long> CONTEXT_MESSAGE_COUNT = AttributeKey.longKey("ragent.context.message_count");
    public static final AttributeKey<Long> CONTEXT_CHARS = AttributeKey.longKey("ragent.context.chars");

    /**
     * 追踪契约版本，只写根 span；语义换代后查询侧凭它区分两代数据，不必靠某个属性有没有来猜
     */
    public static final AttributeKey<String> CONTRACT_VERSION = AttributeKey.stringKey("ragent.contract.version");

    public static final String CONTRACT_VERSION_VALUE = "2";

    /**
     * 批次号，与 PG 块、SSE 帧里的 batchId 是同一个值，由 AgentToolExecutionFacts 独家生成
     */
    public static final AttributeKey<String> TOOL_BATCH_ID = AttributeKey.stringKey("ragent.tool.batch.id");

    public static final AttributeKey<Long> TOOL_BATCH_SIZE = AttributeKey.longKey("ragent.tool.batch.size");

    /**
     * 批级结局只回答整批「跑没跑」，逐条真相在各自的工具节点上
     */
    public static final AttributeKey<String> TOOL_BATCH_OUTCOME = AttributeKey.stringKey("ragent.tool.batch.outcome");

    public static final String OUTCOME_EXECUTED = "executed";
    public static final String OUTCOME_AWAITING = "awaiting";
    public static final String OUTCOME_DENIED = "denied";
    public static final String OUTCOME_INTERRUPTED = "interrupted";

    /**
     * 工具七态，与 PG 块、SSE 帧里的 status 同一套取值
     */
    public static final AttributeKey<String> TOOL_STATUS = AttributeKey.stringKey("ragent.tool.status");

    /**
     * 组内序号，同批并行时靠它把同名的两条分开
     */
    public static final AttributeKey<Long> TOOL_CALL_INDEX = AttributeKey.longKey("ragent.tool.call_index");

    /**
     * span 到底盖住了多少：body 只有工具执行体，查表、校验、排队、重试都在它外面
     */
    public static final AttributeKey<String> TOOL_SPAN_SCOPE = AttributeKey.stringKey("ragent.tool.span_scope");

    public static final String SPAN_SCOPE_BODY = "body";

    /**
     * 失败来源，只在错误时写；error.type 是闭集，细分放这里
     */
    public static final AttributeKey<String> TOOL_ERROR_SOURCE = AttributeKey.stringKey("ragent.tool.error.source");

    /**
     * 工具自己返回了错误态结果
     */
    public static final String ERROR_SOURCE_TOOL_RESULT = "tool_result_error";

    /**
     * 工具体抛出了异常，异常类名在 error.type 里
     */
    public static final String ERROR_SOURCE_EXCEPTION = "exception";

    private RagentAttributes() {
    }
}

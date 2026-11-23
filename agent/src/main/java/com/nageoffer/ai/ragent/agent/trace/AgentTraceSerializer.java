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

import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 追踪内容序列化：把消息块、工具定义、采样参数摊成 LangFuse 能展开的 JSON
 * 两道上限，字段上限逐叶子裁免得最靠后的工具结果被整块吞掉，属性上限管住最终那一整串
 * 也是内容采集的唯一闸门：关掉时载有业务数据的方法一律返回 null，写属性即成空操作
 */
@Slf4j
class AgentTraceSerializer {

    /**
     * hutool 默认无序，显式开启才能保住 role 在 blocks 之前这类可读性
     */
    private static final JSONConfig JSON_CONFIG = JSONConfig.create().setOrder(true);

    /**
     * 序列化失败时的占位，宁可丢一条属性也不能让追踪把对话流打断
     */
    private static final String FALLBACK = "<serialize-failed>";

    /**
     * 溢出对象自身的键名与结构开销，预留出来才能保证退化结果落在预算内
     */
    private static final int OVERFLOW_OVERHEAD_CHARS = 128;

    /**
     * 入参解析不出来时的替身，明确表示这次没有收到完整 JSON
     */
    private static final String MALFORMED = "<malformed-json>";

    private final int maxFieldChars;

    private final int maxAttributeChars;

    private final boolean captureContent;

    AgentTraceSerializer(int maxFieldChars, int maxAttributeChars, boolean captureContent) {
        this.maxFieldChars = maxFieldChars;
        this.maxAttributeChars = maxAttributeChars;
        this.captureContent = captureContent;
    }

    /**
     * 模型这次实际看到的完整消息列表，人设、记忆块、摘要、工具结果都在里面
     */
    String messages(List<Msg> messages) {
        if (!captureContent) {
            return null;
        }
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }
        return budgeted(toJson(messages.stream().map(this::describeMessage).toList()));
    }

    /**
     * 根节点表达整次调用的业务结果，模型原始正文与思考由 generation 保存，不在这里再复制一份。
     * 正常结束只需 answer；非正常结局或带工具/数据等结构化块时才保留 final，
     * 否则权限暂停会丢掉「在等哪个工具、什么参数」这类正文无法表达的信息。
     * final 不摊原始 metadata：那里面是 _chat_usage 这类框架内部键，用量各 generation 已自报，
     * 跟到根上既会被 LangFuse 的 observation 求和重复计入，也把业务结果冲淡。
     */
    String rootOutput(CharSequence answer, Msg finalMsg) {
        if (!captureContent) {
            return null;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("answer", truncate(answer));
        GenerateReason reason = finalMsg == null ? null : finalMsg.getGenerateReason();
        if (shouldKeepFinal(answer, finalMsg, reason)) {
            output.put("final", describeRoleAndBlocks(finalMsg));
        }
        // 停在哪一步只有它说得清：PERMISSION_ASKING、MAX_ITERATIONS、INTERRUPTED 各是完全不同的结局
        output.put("generate_reason", reason == null ? null : reason.name());
        return budgeted(toJson(output));
    }

    /**
     * MODEL_STOP 的纯文本/思考块已分别存在 answer 与 generation，重复展开只会增加检索噪音。
     * 其余结局保守保留完整 final；即使未来框架新增了结构块，正常结束也不会把它静默吞掉。
     */
    private boolean shouldKeepFinal(CharSequence answer, Msg finalMsg, GenerateReason reason) {
        if (finalMsg == null) {
            return false;
        }
        if (reason != GenerateReason.MODEL_STOP) {
            return true;
        }
        List<ContentBlock> content = finalMsg.getContent();
        if (content == null || content.isEmpty()) {
            return false;
        }
        if (content.stream().anyMatch(block -> !(block instanceof TextBlock) && !(block instanceof ThinkingBlock))) {
            return true;
        }
        String finalText = content.stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
        // 极端情况下框架只发最终结果、不发 delta；这时 final 是答案的唯一副本，不能为了去重把它删掉
        return !finalText.isEmpty() && (answer == null || !answer.toString().endsWith(finalText));
    }

    /**
     * 工具定义全量，只在根 span 写一次，逐轮重复几十份 schema 没有意义
     * 故意不设 captureContent 闸门：schema 是装配出来的配置，没有一个字段来自会话，别跟着内容开关一起关掉
     */
    String toolDefinitions(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return "[]";
        }
        List<Object> described = new ArrayList<>(tools.size());
        for (ToolSchema tool : tools) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.getName());
            item.put("description", truncate(tool.getDescription()));
            item.put("parameters", tool.getParameters());
            described.add(item);
        }
        return budgeted(toJson(described));
    }

    /**
     * 采样参数按白名单取：GenerateOptions 上挂着 apiKey 与 baseUrl，整体序列化会把密钥写进追踪库
     * 白名单里全是调参，同样不设 captureContent 闸门：关了内容还能对着参数复现这次调用
     */
    String modelParameters(GenerateOptions options) {
        if (options == null) {
            return "{}";
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", options.getModelName());
        params.put("stream", options.getStream());
        params.put("temperature", options.getTemperature());
        params.put("top_p", options.getTopP());
        params.put("top_k", options.getTopK());
        params.put("max_tokens", options.getMaxTokens());
        params.put("max_completion_tokens", options.getMaxCompletionTokens());
        params.put("thinking_budget", options.getThinkingBudget());
        params.put("reasoning_effort", options.getReasoningEffort());
        params.put("parallel_tool_calls", options.getParallelToolCalls());
        params.put("tool_choice", options.getToolChoice() == null ? null : options.getToolChoice().toString());
        params.put("seed", options.getSeed());
        return budgeted(toJson(params));
    }

    /**
     * 一批工具的名册，入参与结果共用它：批 span 是这批工具在树上唯一的节点，逐条只能都摊在它身上
     * 条目由调用处按 callIndex 排好并逐字段截断，这里只负责摊成数组并守住属性总预算
     */
    String toolCalls(List<Map<String, Object>> calls) {
        if (!captureContent) {
            return null;
        }
        if (calls == null || calls.isEmpty()) {
            return "[]";
        }
        return budgeted(toJson(calls));
    }

    /**
     * 工具入参含员工姓名、申请单号这类真实业务字段，限额内保留嵌套结构，超限才降级成截断串
     */
    Object arguments(Map<String, Object> input) {
        return captureContent ? compact(input) : null;
    }

    /**
     * 流式拼出来的那份入参：模型逐片吐的是 JSON 原文，先解析回结构再交给上面那条，保证与完整工具入参形态一致
     * 解析不出来说明流被截断在半路，用明确的占位避免后端把半截 JSON 当成正常参数
     */
    Object argumentsFromRaw(String raw) {
        if (!captureContent) {
            return null;
        }
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return compact(JSONUtil.parseObj(raw, JSON_CONFIG));
        } catch (Exception ex) {
            log.debug("工具入参 JSON 解析失败, 长度: {}", raw.length(), ex);
            return MALFORMED;
        }
    }

    /**
     * 单个工具自己那份入参：与批名册共用同一套限额，区别只在这里落的是整条属性而非嵌套值
     * 同名并行的两条各写各的，靠 span 自带的 toolCallId 与 callIndex 分辨，不必再往内容里塞一遍
     */
    String toolArguments(Map<String, Object> input) {
        if (!captureContent) {
            return null;
        }
        if (input == null || input.isEmpty()) {
            return "{}";
        }
        return budgeted(toJson(compact(input)));
    }

    /**
     * 规范里的工具返回值只要正文，状态另有专键，故这份不带 state
     */
    String toolResult(ToolResultBlock result) {
        if (!captureContent || result == null) {
            return null;
        }
        return budgeted(toJson(describeBlock(result)));
    }

    /**
     * 给 UI 读的那份结局：状态、结果与异常同在一份里，失败与抛异常都走这条
     * 不为「只有异常」另开一个出口，那样读的人得先猜这次落在哪个字段上
     */
    String toolOutcome(String state, ToolResultBlock result, Throwable error) {
        if (!captureContent) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("state", state);
        item.put("result", result == null ? null : describeBlock(result));
        item.put("error", error == null ? null : truncate(error.toString()));
        return budgeted(toJson(item));
    }

    /**
     * 模型本轮的输出：正文、思考、工具调用三段各自成键
     */
    String modelOutput(CharSequence text, CharSequence thinking, List<Map<String, Object>> toolCalls) {
        if (!captureContent) {
            return null;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("text", truncate(text));
        output.put("thinking", truncate(thinking));
        output.put("tool_calls", toolCalls == null || toolCalls.isEmpty() ? null : toolCalls);
        return budgeted(toJson(output));
    }

    /**
     * 键名用 LangFuse 认得的那几个，才会被算进成本与用量统计，改成驼峰就只是普通元数据了
     * 各键之间必须互不重叠：ChatUsage 的 inputTokens 含缓存读写两部分，直接写会把缓存算两遍
     * input_cached_tokens 是 LangFuse 自己归一化 OpenAI 用量时产出的键名，跟着它才对得上模型定价
     * 缓存写入只有上游报了才写，缺这个键表示未知，不是零
     * 第三处不设 captureContent 闸门的：token 数是计量口径，补上闸门等于关内容的同时把成本面板一起关了
     */
    String usageDetails(int input, int output, int cached, Integer cacheWrite, int total) {
        int safeCached = Math.max(0, Math.min(cached, input));
        int safeWrite = cacheWrite == null ? 0 : Math.max(0, Math.min(cacheWrite, input - safeCached));
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input", input - safeCached - safeWrite);
        usage.put("input_cached_tokens", safeCached);
        if (cacheWrite != null) {
            usage.put("cache_creation_input_tokens", safeWrite);
        }
        usage.put("output", output);
        usage.put("total", total);
        return toJson(usage);
    }

    /**
     * 叶子字段上限，只裁一个字段，外层结构不受影响
     */
    String truncate(CharSequence raw) {
        return cut(raw, maxFieldChars);
    }

    /**
     * 整条纯文本属性：纯文本没有结构，直接砍砍不坏，两道上限取小者即可
     */
    String plainText(CharSequence raw) {
        return captureContent ? cut(raw, Math.min(maxFieldChars, maxAttributeChars)) : null;
    }

    /**
     * 收 CharSequence 而非 String：流式正文攒在 StringBuilder 里，每刷一次都 toString 等于按已攒长度
     * 复制一遍，一段长回答刷下来就是平方开销；这里只截上限内的前缀，单次成本与已攒长度无关
     */
    private static String cut(CharSequence raw, int limit) {
        if (raw == null) {
            return null;
        }
        if (raw.length() <= limit) {
            return raw.toString();
        }
        return raw.subSequence(0, limit) + "…[truncated " + (raw.length() - limit) + " chars]";
    }

    /**
     * 属性总预算：字段上限只管一个叶子，几十条消息各自合规，拼起来照样能把一条属性撑到几兆
     * 超限不能直接砍字符串，砍出来的是半截 JSON，LangFuse 解析不了只会当整块纯文本贴出来
     * 故整段退成一个合法对象，正文塞进字符串值里由序列化器负责转义，长度不达标就再对半收
     */
    private String budgeted(String json) {
        if (json == null || json.length() <= maxAttributeChars) {
            return json;
        }
        int preview = Math.max(0, maxAttributeChars - OVERFLOW_OVERHEAD_CHARS);
        while (preview > 0) {
            String reduced = toJson(overflow(json, preview));
            if (FALLBACK.equals(reduced) || reduced.length() <= maxAttributeChars) {
                return reduced;
            }
            preview /= 2;
        }
        return toJson(overflow(json, 0));
    }

    private static Map<String, Object> overflow(String json, int preview) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("_truncated", true);
        item.put("original_chars", json.length());
        item.put("preview", json.substring(0, Math.min(preview, json.length())));
        return item;
    }

    /**
     * 角色与内容块，两个渲染出口的公共部分；根节点的 final 只要这些，ASKING 工具块在 content 里
     */
    private Map<String, Object> describeRoleAndBlocks(Msg msg) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", msg.getRole() == null ? null : msg.getRole().name().toLowerCase(Locale.ROOT));
        item.put("name", msg.getName());
        item.put("blocks", describeBlocks(msg.getContent()));
        return item;
    }

    private Map<String, Object> describeMessage(Msg msg) {
        Map<String, Object> item = describeRoleAndBlocks(msg);
        // 确认续跑那条消息一个内容块都没有，批准还是拒绝只存在于 metadata 里
        if (msg.getMetadata() != null && !msg.getMetadata().isEmpty()) {
            item.put("metadata", compact(msg.getMetadata()));
        }
        return item;
    }

    private List<Object> describeBlocks(List<ContentBlock> content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        return content.stream().map(this::describeBlock).toList();
    }

    private Object describeBlock(ContentBlock block) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (block instanceof TextBlock text) {
            item.put("type", "text");
            item.put("text", truncate(text.getText()));
            return item;
        }
        if (block instanceof ThinkingBlock thinking) {
            item.put("type", "thinking");
            item.put("thinking", truncate(thinking.getThinking()));
            return item;
        }
        if (block instanceof ToolUseBlock toolUse) {
            item.put("type", "tool_use");
            item.put("id", toolUse.getId());
            item.put("name", toolUse.getName());
            item.put("state", toolUse.getState() == null ? null : toolUse.getState().name());
            item.put("input", compact(toolUse.getInput()));
            return item;
        }
        if (block instanceof ToolResultBlock result) {
            item.put("type", "tool_result");
            item.put("id", result.getId());
            item.put("name", result.getName());
            item.put("state", result.getState() == null ? null : result.getState().name());
            item.put("output", describeBlocks(result.getOutput()));
            return item;
        }
        // 未知块类型不吞掉，退回 toString 至少留下痕迹
        item.put("type", block == null ? "null" : block.getClass().getSimpleName());
        item.put("value", truncate(String.valueOf(block)));
        return item;
    }

    /**
     * 入参与元数据在限额内保留嵌套结构，超限才降级成截断字符串
     * 序列化失败时必须把降级串交出去：还回原对象只会让外层再失败一次，整条属性一起陪葬
     */
    private Object compact(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String json = toJson(input);
        if (FALLBACK.equals(json)) {
            return json;
        }
        return json.length() <= maxFieldChars ? input : truncate(json);
    }

    private String toJson(Object value) {
        try {
            return JSONUtil.toJsonStr(value, JSON_CONFIG);
        } catch (Exception e) {
            log.warn("追踪内容序列化失败, 本条属性降级", e);
            return FALLBACK;
        }
    }
}

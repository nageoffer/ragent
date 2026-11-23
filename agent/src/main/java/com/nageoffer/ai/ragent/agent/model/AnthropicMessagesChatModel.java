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

package com.nageoffer.ai.ragent.agent.model;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 按 Anthropic Messages 协议调用 Claude：地址、密钥与模型名（含网关的线路后缀）都来自配置，类里不写死网关
 * 思考块连同签名原样回传，挂在本步第一个工具调用的元数据上，SDK 聚合和落库都带得住；档位走 output_config.effort
 * 采样参数一律不发：Opus 5.5 只接受默认值，摘要器与 Judge 写死的 temperature=0.3 发过去就是 400
 * 不用 AgentScope 自带的 Anthropic 扩展：它不回传思考签名、照发采样参数
 */
@Slf4j
public class AnthropicMessagesChatModel extends ChatModelBase {

    /**
     * 缓存写入 token 数放在响应元数据里交给追踪，SDK 2.0.2 的 ChatUsage 没有这一项
     */
    public static final String METADATA_CACHE_WRITE_TOKENS = "cacheWriteTokens";

    /**
     * 本步的思考原块（thinking / redacted_thinking），回传时一字不改，改了签名校验不过
     */
    static final String METADATA_THINKING_BLOCKS = "anthropicThinkingBlocks";

    static final String PROVIDER = "anthropic";

    /**
     * 协议必填；不传时网关补 4096，high 档思考加长回答会被截断，按实际生成量计费，给大不多花钱
     */
    static final int DEFAULT_MAX_TOKENS = 32000;

    private static final String API_VERSION = "2023-06-01";

    /**
     * 技能遮蔽、记忆块、裁剪与压缩都会改动思考块之前的内容，有前缀校验的型号对这种失配可能 400、也可能把失效思考静默喂给模型
     * 每次请求都要求丢掉失效块，模型从丢的位置重新思考；只处理前缀失配，签名损坏、块顺序错乱等照旧报错或无法恢复
     */
    private static final String THINKING_BINDING_BETA = "thinking-binding-controls-2026-08-01";

    private static final String PREFIX_MISMATCH = "prefix_binding_mismatch";

    /**
     * 正常结束的原因，换成 OpenAI 口径交给上层，摘要器与 Judge 只认 stop；不在表里的一律算回复不完整
     */
    private static final Map<String, String> FINISH_REASONS = Map.of(
            "end_turn", "stop", "stop_sequence", "stop", "tool_use", "tool_calls");

    private static final String TOOL_USE = "tool_use";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 参数串要整段恰好是一个对象，"{...}{..." 这种只读第一个对象就放行会让工具拿错参数
     */
    private static final ObjectReader STRICT = MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final String url;
    private final String apiKey;
    private final String modelName;
    private final HttpTransport transport;
    private final GenerateOptions defaultOptions;

    public AnthropicMessagesChatModel(String url, String apiKey, String modelName) {
        this(url, apiKey, modelName, HttpTransportFactory.getDefault());
    }

    public AnthropicMessagesChatModel(String url, String apiKey, String modelName, HttpTransport transport) {
        this.url = url;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.transport = transport;
        // 与 OpenAIChatModel 同口径：模型级默认超时与尝试次数垫底，调用级的 agent.max-retries 覆盖它
        this.defaultOptions = ModelUtils.ensureDefaultExecutionConfig(null);
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        GenerateOptions effective = GenerateOptions.mergeOptions(options, defaultOptions);
        boolean stream = !Boolean.FALSE.equals(effective.getStream());
        HttpRequest.Builder request = HttpRequest.builder()
                .url(url)
                .method("POST")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("anthropic-beta", THINKING_BINDING_BETA)
                .header("Content-Type", "application/json")
                .body(requestBody(messages, tools, effective, stream));
        HttpRequest built = request.build();
        Instant start = Instant.now();
        // 主循环经 Agent 调用时上下文里有会话，日志才对得上是哪次对话；摘要器与 Judge 直接调用，取不到就留空
        Flux<ChatResponse> responses = Flux.deferContextual(view -> {
            RuntimeContext context = view.getOrDefault(AgentBase.RUNTIME_CONTEXT_KEY, null);
            ResponseState state = new ResponseState(start, context == null ? null : context.getSessionId());
            return stream
                    ? transport.stream(built).concatMapIterable(state::accept).concatWith(Flux.defer(state::ensureStopped))
                    : Flux.just(state.fromMessage(execute(built)));
        });
        return ModelUtils.applyTimeoutAndRetry(responses.subscribeOn(Schedulers.boundedElastic()),
                options, defaultOptions, modelName, PROVIDER);
    }

    String requestBody(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode()
                .put("model", modelName)
                .put("max_tokens", options.getMaxTokens() != null ? options.getMaxTokens() : DEFAULT_MAX_TOKENS)
                .put("stream", stream);
        List<String> system = new ArrayList<>();
        ArrayNode turns = MAPPER.createArrayNode();
        for (Msg msg : messages) {
            appendMessage(msg, system, turns);
        }
        // 断点放法与 SDK 的 applyCacheControl 相同：系统提示词一个，末条消息一个
        if (!system.isEmpty()) {
            body.putArray("system").add(markCached(textBlock(String.join("\n\n", system))));
        }
        markLastBlockCached(turns);
        body.set("messages", turns);
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolArray = body.putArray("tools");
            for (ToolSchema tool : tools) {
                ObjectNode node = toolArray.addObject().put("name", tool.getName()).put("description", tool.getDescription());
                node.set("input_schema", tool.getParameters() != null
                        ? MAPPER.valueToTree(tool.getParameters())
                        : MAPPER.createObjectNode().put("type", "object"));
            }
        }
        if (StrUtil.isNotBlank(options.getReasoningEffort())) {
            body.putObject("output_config").put("effort", options.getReasoningEffort());
        }
        // block_binding 只能挂在 adaptive 下；adaptive 本就是 Opus 与 Sonnet 5.5 不写 thinking 时的默认，显式写出不改变思考行为
        body.putObject("thinking").put("type", "adaptive")
                .putObject("block_binding").put("prefix_mismatch_behavior", "drop_block");
        return body.toString();
    }

    private void appendMessage(Msg msg, List<String> system, ArrayNode turns) {
        List<ToolResultBlock> results = msg.getContentBlocks(ToolResultBlock.class);
        if (!results.isEmpty()) {
            for (ToolResultBlock result : results) {
                ObjectNode node = MAPPER.createObjectNode()
                        .put("type", "tool_result")
                        .put("tool_use_id", result.getId())
                        .put("content", textOf(result.getOutput()));
                // 用户拒绝确认卡的结果正文里已有解释，不算工具出错
                if (result.getState() == ToolResultState.ERROR) {
                    node.put("is_error", true);
                }
                addToTurn(turns, "user", node);
            }
            return;
        }
        if (msg.getRole() == MsgRole.SYSTEM) {
            system.add(textOf(msg.getContent()));
        } else if (msg.getRole() == MsgRole.USER) {
            for (ContentBlock block : msg.getContent()) {
                String text = textOf(List.of(block));
                if (StrUtil.isNotBlank(text)) {
                    addToTurn(turns, "user", textBlock(text));
                }
            }
        } else if (msg.getRole() == MsgRole.ASSISTANT) {
            appendAssistant(msg, turns);
        }
    }

    /**
     * 顺序照原响应：思考块、正文、工具调用；思考块只从工具调用的元数据里取，ThinkingBlock 里那份是给界面看的
     */
    private void appendAssistant(Msg msg, ArrayNode turns) {
        List<ObjectNode> blocks = new ArrayList<>();
        List<ToolUseBlock> calls = msg.getContentBlocks(ToolUseBlock.class);
        for (ToolUseBlock call : calls) {
            Object thinking = call.getMetadata() == null ? null : call.getMetadata().get(METADATA_THINKING_BLOCKS);
            if (thinking instanceof List<?> raw) {
                raw.forEach(block -> blocks.add(MAPPER.valueToTree(block)));
            }
        }
        for (TextBlock text : msg.getContentBlocks(TextBlock.class)) {
            if (StrUtil.isNotBlank(text.getText())) {
                blocks.add(textBlock(text.getText()));
            }
        }
        for (ToolUseBlock call : calls) {
            ObjectNode node = MAPPER.createObjectNode().put("type", "tool_use").put("id", call.getId()).put("name", call.getName());
            node.set("input", MAPPER.valueToTree(call.getInput() != null ? call.getInput() : Map.of()));
            blocks.add(node);
        }
        blocks.forEach(block -> addToTurn(turns, "assistant", block));
    }

    /**
     * 同角色连续的消息并进一个回合：工具结果必须紧跟在调用它的那步之后，并行工具的结果要在同一回合里
     */
    private static void addToTurn(ArrayNode turns, String role, ObjectNode block) {
        JsonNode last = turns.isEmpty() ? null : turns.get(turns.size() - 1);
        if (last != null && role.equals(last.path("role").asText())) {
            ((ArrayNode) last.get("content")).add(block);
            return;
        }
        ObjectNode turn = turns.addObject().put("role", role);
        turn.putArray("content").add(block);
    }

    /**
     * 思考块不接受 cache_control，末块是思考块时这一处断点就不打
     */
    private static void markLastBlockCached(ArrayNode turns) {
        if (turns.isEmpty()) {
            return;
        }
        ArrayNode content = (ArrayNode) turns.get(turns.size() - 1).get("content");
        ObjectNode last = (ObjectNode) content.get(content.size() - 1);
        if (!last.path("type").asText().endsWith("thinking")) {
            markCached(last);
        }
    }

    private static ObjectNode markCached(ObjectNode block) {
        block.putObject("cache_control").put("type", "ephemeral");
        return block;
    }

    private static ObjectNode textBlock(String text) {
        return MAPPER.createObjectNode().put("type", "text").put("text", text);
    }

    private static String textOf(List<ContentBlock> blocks) {
        if (blocks == null) {
            return "";
        }
        return blocks.stream()
                .map(block -> block instanceof TextBlock text ? text.getText()
                        : block instanceof HintBlock hint ? hint.getHint() : null)
                .filter(StrUtil::isNotEmpty)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 与 SDK 传输流式出错同口径：状态码给 SDK 判断能否重试，响应体由异常自己拼进消息，网关的拒绝理由在日志里看得到
     */
    private JsonNode execute(HttpRequest request) {
        HttpResponse response = transport.execute(request);
        if (!response.isSuccessful()) {
            throw new HttpTransportException("Anthropic Messages 请求失败: HTTP " + response.getStatusCode(),
                    response.getStatusCode(), response.getBody());
        }
        return readTree(response.getBody());
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ModelException("Anthropic Messages 响应不是合法 JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * 一次响应的解析状态，流式与非流式共用；每次订阅新建一份，模型实例上不存
     */
    private final class ResponseState {

        private final Instant start;
        private final String sessionId;
        private JsonNode inputTransformations;
        private final List<Map<String, Object>> thinkingBlocks = new ArrayList<>();
        /**
         * 流里的工具调用块，按块序号记入参分片与是否收尾，收尾时校验参数完整
         */
        private final Map<Integer, StreamedToolUse> toolUses = new LinkedHashMap<>();
        private int openBlock = -1;
        private boolean stopped;
        private Map<String, Object> openThinking;
        private String messageId;
        private String stopReason;
        private int inputTokens;
        private int cacheReadTokens;
        private int cacheWriteTokens;
        private int outputTokens;

        private ResponseState(Instant start, String sessionId) {
            this.start = start;
            this.sessionId = sessionId;
        }

        List<ChatResponse> accept(String data) {
            JsonNode event = readTree(data);
            if (stopped) {
                throw incomplete("message_stop 之后还有事件");
            }
            switch (event.path("type").asText()) {
                case "message_start" -> {
                    messageId = event.path("message").path("id").asText(null);
                    mergeUsage(event.path("message").path("usage"));
                    mergeTransformations(event.path("message"));
                }
                case "content_block_start" -> {
                    openBlock = event.path("index").asInt();
                    return blockStart(event.path("content_block"));
                }
                case "content_block_delta" -> {
                    return blockDelta(event.path("index").asInt(), event.path("delta"));
                }
                case "content_block_stop" -> {
                    closeThinking();
                    StreamedToolUse toolUse = toolUses.get(event.path("index").asInt());
                    if (toolUse != null) {
                        toolUse.closed = true;
                    }
                }
                case "message_delta" -> {
                    stopReason = event.path("delta").path("stop_reason").asText(stopReason);
                    mergeUsage(event.path("usage"));
                    mergeTransformations(event);
                }
                case "message_stop" -> {
                    stopped = true;
                    ensureComplete(toolUses.values().stream().allMatch(StreamedToolUse::complete));
                    return List.of(finish(List.of()));
                }
                case "error" -> throw new ModelException("Anthropic Messages 流内报错: " + event.path("error"), modelName, PROVIDER);
                default -> {
                }
            }
            return List.of();
        }

        ChatResponse fromMessage(JsonNode message) {
            messageId = message.path("id").asText(null);
            stopReason = message.path("stop_reason").asText(null);
            mergeUsage(message.path("usage"));
            mergeTransformations(message);
            List<ContentBlock> content = new ArrayList<>();
            boolean argumentsComplete = true;
            for (JsonNode block : message.path("content")) {
                switch (block.path("type").asText()) {
                    case "thinking", "redacted_thinking" -> thinkingBlocks.add(toMap(block));
                    case "text" -> content.add(TextBlock.builder().text(block.path("text").asText()).build());
                    case "tool_use" -> {
                        argumentsComplete &= block.path("input").isObject();
                        content.add(ToolUseBlock.builder()
                                .id(block.path("id").asText())
                                .name(block.path("name").asText())
                                .input(toMap(block.path("input")))
                                .content(block.path("input").toString())
                                .metadata(claimThinking())
                                .build());
                    }
                    default -> {
                    }
                }
            }
            ensureComplete(argumentsComplete);
            return finish(content);
        }

        /**
         * 流没等到 message_stop 就断了：连接提前断开或网关异常，回复不完整
         */
        Flux<ChatResponse> ensureStopped() {
            if (!stopped) {
                throw incomplete("流在 message_stop 之前结束");
            }
            return Flux.empty();
        }

        /**
         * 半截回复不交给上层：带工具调用的只认 tool_use，且每个调用的参数都完整；纯文字只认正常结束
         * 输出上限、拒答、没见过的原因都算不完整，否则工具会拿着半截参数被执行
         */
        private void ensureComplete(boolean argumentsComplete) {
            boolean hasToolUse = !toolUses.isEmpty() || TOOL_USE.equals(stopReason);
            boolean expected = hasToolUse ? TOOL_USE.equals(stopReason) : FINISH_REASONS.containsKey(stopReason);
            if (!expected) {
                throw incomplete("结束原因 " + stopReason);
            }
            if (!argumentsComplete) {
                throw incomplete("工具调用参数不完整");
            }
        }

        private ModelException incomplete(String reason) {
            return new ModelException("Claude 回复不完整: " + reason + ", 响应: " + messageId, modelName, PROVIDER);
        }

        private List<ChatResponse> blockStart(JsonNode block) {
            switch (block.path("type").asText()) {
                case "thinking" -> openThinking = new LinkedHashMap<>(Map.of("type", "thinking", "thinking", "", "signature", ""));
                case "redacted_thinking" -> thinkingBlocks.add(toMap(block));
                case "tool_use" -> {
                    // 入参一般随后按片到、起始块给 {}；起始块已带完整入参时原样交下去，空对象不交，否则 "{}" 会和后面的分片拼坏
                    JsonNode input = block.path("input");
                    String initial = input.isObject() && !input.isEmpty() ? input.toString() : "";
                    toolUses.put(openBlock, new StreamedToolUse(initial, input.isObject()));
                    return List.of(chunk(ToolUseBlock.builder()
                            .id(block.path("id").asText())
                            .name(block.path("name").asText())
                            .input(initial.isEmpty() ? Map.of() : toMap(input))
                            .content(initial)
                            .metadata(claimThinking())
                            .build()));
                }
                default -> {
                }
            }
            return List.of();
        }

        private List<ChatResponse> blockDelta(int index, JsonNode delta) {
            switch (delta.path("type").asText()) {
                case "text_delta" -> {
                    return textChunk(delta.path("text").asText());
                }
                case "thinking_delta" -> {
                    String text = delta.path("thinking").asText();
                    append(openThinking, "thinking", text);
                    return text.isEmpty() ? List.of() : List.of(chunk(ThinkingBlock.builder().thinking(text).build()));
                }
                case "signature_delta" -> append(openThinking, "signature", delta.path("signature").asText());
                case "input_json_delta" -> {
                    String part = delta.path("partial_json").asText();
                    StreamedToolUse toolUse = toolUses.get(index);
                    // SDK 把分片归给最近开头的调用，分片属于别的调用就会串参
                    if (toolUse == null || index != openBlock) {
                        throw incomplete("工具调用参数分片交错");
                    }
                    toolUse.arguments.append(part);
                    // SDK 的分片约定：空 id 加占位名，归到最近一个开头的工具调用
                    return part.isEmpty() ? List.of() : List.of(chunk(ToolUseBlock.builder()
                            .id("")
                            .name("__fragment__")
                            .input(Map.of())
                            .content(part)
                            .build()));
                }
                default -> {
                }
            }
            return List.of();
        }

        private List<ChatResponse> textChunk(String text) {
            return text.isEmpty() ? List.of() : List.of(chunk(TextBlock.builder().text(text).build()));
        }

        private void closeThinking() {
            if (openThinking != null) {
                thinkingBlocks.add(openThinking);
                openThinking = null;
            }
        }

        /**
         * 同一步并行调用多个工具时只挂在第一个上，回传时照样排在全部调用之前
         */
        private Map<String, Object> claimThinking() {
            if (thinkingBlocks.isEmpty()) {
                return null;
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(METADATA_THINKING_BLOCKS, new ArrayList<>(thinkingBlocks));
            thinkingBlocks.clear();
            return metadata;
        }

        /**
         * 流里 message_start 与 message_delta 各报一次，后到的覆盖先到的
         */
        private void mergeUsage(JsonNode usage) {
            inputTokens = usage.path("input_tokens").asInt(inputTokens);
            cacheReadTokens = usage.path("cache_read_input_tokens").asInt(cacheReadTokens);
            cacheWriteTokens = usage.path("cache_creation_input_tokens").asInt(cacheWriteTokens);
            outputTokens = usage.path("output_tokens").asInt(outputTokens);
        }

        private void mergeTransformations(JsonNode holder) {
            JsonNode value = holder.path("input_transformations");
            if (value.isArray()) {
                inputTransformations = value;
            }
        }

        /**
         * 前缀失配被丢块是技能遮蔽等已知改动的正常结果，记 info；其他类型或原因不在预期内，记 warn 留待排查
         */
        private void logTransformations() {
            if (inputTransformations == null || inputTransformations.isEmpty()) {
                return;
            }
            boolean expected = true;
            for (JsonNode item : inputTransformations) {
                expected &= "thinking_dropped".equals(item.path("type").asText())
                        && PREFIX_MISMATCH.equals(item.path("reason").asText());
            }
            if (expected) {
                log.info("Claude 前缀失配的思考块已丢弃, 会话: {}, 响应: {}, 明细: {}", sessionId, messageId, inputTransformations);
            } else {
                log.warn("Claude 输入出现非预期转换, 会话: {}, 响应: {}, 明细: {}", sessionId, messageId, inputTransformations);
            }
        }

        /**
         * Anthropic 的 input_tokens 不含缓存读写，这里加回去，跟 OpenAI 口径一样让输入总量含缓存部分
         */
        private ChatResponse finish(List<ContentBlock> content) {
            logTransformations();
            ChatUsage usage = ChatUsage.builder()
                    .inputTokens(inputTokens + cacheReadTokens + cacheWriteTokens)
                    .outputTokens(outputTokens)
                    .cachedTokens(cacheReadTokens)
                    .time(Duration.between(start, Instant.now()).toMillis() / 1000.0)
                    .build();
            return ChatResponse.builder()
                    .id(messageId)
                    .content(content)
                    .usage(usage)
                    .metadata(Map.of(METADATA_CACHE_WRITE_TOKENS, cacheWriteTokens))
                    .finishReason(FINISH_REASONS.get(stopReason))
                    .build();
        }

        private ChatResponse chunk(ContentBlock block) {
            return ChatResponse.builder().id(messageId).content(List.of(block)).build();
        }

        private void append(Map<String, Object> block, String key, String part) {
            if (block != null) {
                block.put(key, block.get(key) + part);
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> toMap(JsonNode node) {
            return MAPPER.convertValue(node, LinkedHashMap.class);
        }
    }

    /**
     * 流里一个工具调用块：起始块自带的 input 是对象、块已收尾、拼好的分片能解析成对象，参数才算完整
     */
    private static final class StreamedToolUse {

        private final boolean startedWithObject;
        private final StringBuilder arguments;
        private boolean closed;

        private StreamedToolUse(String initial, boolean startedWithObject) {
            this.arguments = new StringBuilder(initial);
            this.startedWithObject = startedWithObject;
        }

        /**
         * 无参工具不发分片，只认起始块明确给出的 input 对象
         */
        private boolean complete() {
            if (!closed) {
                return false;
            }
            if (arguments.isEmpty()) {
                return startedWithObject;
            }
            try {
                return STRICT.readTree(arguments.toString()).isObject();
            } catch (IOException e) {
                return false;
            }
        }
    }
}

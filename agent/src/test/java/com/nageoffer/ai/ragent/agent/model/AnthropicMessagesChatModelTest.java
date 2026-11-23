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

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opus 经网关的 Anthropic Messages 入口：用录下的真实流跑完整的 ReActAgent，思考块连同签名得原样回到下一步请求里
 * 网关会校验签名，丢了或改了都会让模型从头再想，篡改的直接 400
 */
class AnthropicMessagesChatModelTest {

    private static final String URL = "https://zenmux.ai/api/anthropic/v1/messages";
    private static final String MODEL = "anthropic/claude-opus-5.5:anthropic";

    /**
     * 录音里没有收尾那步，补一段最短的正文回包
     */
    private static final List<String> ANSWER = List.of(
            "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_answer\",\"usage\":{\"input_tokens\":900,\"output_tokens\":1}}}",
            "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"快递正在派送中\"}}",
            "{\"type\":\"content_block_stop\",\"index\":0}",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":9}}",
            "{\"type\":\"message_stop\"}");

    @Test
    void shouldEchoThinkingBlockBeforeToolUseAcrossStateStore() {
        GatewayReplay.ScriptedTransport gateway = new GatewayReplay.ScriptedTransport()
                .thenStream(GatewayReplay.sse("anthropic-opus-step1.sse"))
                .thenStream(GatewayReplay.sse("anthropic-opus-step2.sse"))
                .thenStream(ANSWER)
                .thenStream(ANSWER);

        GatewayReplay.runTwoTurns(new AnthropicMessagesChatModel(URL, "sk-test", MODEL, gateway));

        assertThat(gateway.requests).hasSize(4);
        String signature = recordedSignature();
        // 同一轮里的第二步：思考块排在工具调用前，正文为空也要带上
        JSONArray secondStep = assistantTurns(gateway.requests.get(1)).getJSONObject(0).getJSONArray("content");
        assertThat(secondStep.getJSONObject(0).getStr("type")).isEqualTo("thinking");
        assertThat(secondStep.getJSONObject(0).getStr("thinking")).isEmpty();
        assertThat(secondStep.getJSONObject(0).getStr("signature")).isEqualTo(signature);
        assertThat(secondStep.getJSONObject(1).getStr("type")).isEqualTo("tool_use");
        assertThat(secondStep.getJSONObject(1).getJSONObject("input").getStr("order_no")).isEqualTo("BT20261120001");
        // 第二轮只能从存下的 JSON 恢复，签名还得在；第二步本来没思考，就不该凭空多出思考块
        JSONArray restored = assistantTurns(gateway.requests.get(3));
        assertThat(restored.getJSONObject(0).getJSONArray("content").getJSONObject(0).getStr("signature"))
                .isEqualTo(signature);
        assertThat(restored.getJSONObject(1).getJSONArray("content").getJSONObject(0).getStr("type"))
                .isEqualTo("tool_use");
    }

    /**
     * 摘要器与 Judge 写死 temperature=0.3，原样发出去 Opus 5.5 直接 400；max_tokens 是协议必填
     */
    @Test
    void shouldDropSamplingParamsAndFillProtocolDefaults() {
        AnthropicMessagesChatModel model = new AnthropicMessagesChatModel(URL, "sk-test", MODEL,
                new GatewayReplay.ScriptedTransport());
        GenerateOptions options = GenerateOptions.builder().temperature(0.3D).topP(0.9D).reasoningEffort("high").build();

        JSONObject body = JSONUtil.parseObj(model.requestBody(List.of(
                Msg.builder().role(MsgRole.SYSTEM).textContent("你是客服").build(),
                Msg.builder().role(MsgRole.USER).textContent("AirPods Pro 左耳没声音").build()), null, options, true));

        assertThat(body.containsKey("temperature")).isFalse();
        assertThat(body.containsKey("top_p")).isFalse();
        assertThat(body.getInt("max_tokens")).isEqualTo(AnthropicMessagesChatModel.DEFAULT_MAX_TOKENS);
        assertThat(body.getJSONObject("output_config").getStr("effort")).isEqualTo("high");
        // 技能遮蔽等改动会让前面的思考块失配，固定要求丢弃，不让 Opus 5.5 在 400 与静默放行之间摇摆
        assertThat(body.getJSONObject("thinking").getJSONObject("block_binding").getStr("prefix_mismatch_behavior"))
                .isEqualTo("drop_block");
        assertThat(body.getJSONArray("system").getJSONObject(0).getJSONObject("cache_control").getStr("type"))
                .isEqualTo("ephemeral");
        assertThat(body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
                .getJSONObject("cache_control").getStr("type")).isEqualTo("ephemeral");
    }

    /**
     * 录音的两笔账单：输入 11、写缓存 2896、输出 3 记 $0.014584；再发一次读缓存 2896 记 $0.0006832
     * Anthropic 的 input_tokens 不含缓存，输入总量要把读写都加回去，写入量另走元数据
     */
    @Test
    void shouldReportCacheWriteAndReadSeparately() {
        GatewayReplay.ScriptedTransport gateway = new GatewayReplay.ScriptedTransport()
                .thenBody(GatewayReplay.resource("anthropic-opus-cache-write.json"))
                .thenBody(GatewayReplay.resource("anthropic-opus-cache-read.json"));
        AnthropicMessagesChatModel model = new AnthropicMessagesChatModel(URL, "sk-test", MODEL, gateway);

        ChatResponse write = callOnce(model);
        ChatResponse read = callOnce(model);

        assertThat(write.getUsage().getInputTokens()).isEqualTo(2907);
        assertThat(write.getUsage().getCachedTokens()).isZero();
        assertThat(write.getMetadata()).containsEntry(AnthropicMessagesChatModel.METADATA_CACHE_WRITE_TOKENS, 2896);
        assertThat(read.getUsage().getInputTokens()).isEqualTo(2907);
        assertThat(read.getUsage().getCachedTokens()).isEqualTo(2896);
        assertThat(read.getMetadata()).containsEntry(AnthropicMessagesChatModel.METADATA_CACHE_WRITE_TOKENS, 0);
        // 摘要器与 Judge 只认 stop 为正常结束
        assertThat(write.getFinishReason()).isEqualTo("stop");
    }

    /**
     * 网关的拒绝理由只在响应体里，异常消息要带上它且只带一份；状态码留在异常上给 SDK 判断能否重试
     */
    @Test
    void shouldCarryRejectReasonOnceWhenGatewayRejects() {
        String reason = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"temperature 不支持\"}}";
        HttpTransport rejecting = new HttpTransport() {
            @Override
            public HttpResponse execute(HttpRequest request) {
                return HttpResponse.builder().statusCode(400).body(reason).build();
            }

            @Override
            public Flux<String> stream(HttpRequest request) {
                return Flux.error(new IllegalStateException("本用例只走非流式"));
            }

            @Override
            public void close() {
            }
        };
        AnthropicMessagesChatModel model = new AnthropicMessagesChatModel(URL, "sk-test", MODEL, rejecting);

        assertThatThrownBy(() -> model.stream(List.of(Msg.builder().role(MsgRole.USER).textContent("问").build()), null,
                GenerateOptions.builder().stream(false)
                        .executionConfig(ExecutionConfig.builder().maxAttempts(1).build()).build()).blockLast())
                .isInstanceOfSatisfying(HttpTransportException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(400);
                    assertThat(e.getMessage().split("temperature 不支持", -1)).hasSize(2);
                });
    }

    /**
     * drop_block 只有带上 beta 头才生效，网关会原样透传；流式时丢块明细在 message_start 里，响应照常出正文
     */
    @Test
    void shouldSendBindingBetaAndKeepStreamingAfterDroppedThinking() {
        GatewayReplay.ScriptedTransport gateway = new GatewayReplay.ScriptedTransport().thenStream(List.of(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"usage\":{\"input_tokens\":900,\"output_tokens\":1},"
                        + "\"input_transformations\":[{\"type\":\"thinking_dropped\",\"path\":\"messages.1.content.0\","
                        + "\"reason\":\"prefix_binding_mismatch\"}]}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"好\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}",
                "{\"type\":\"message_stop\"}"));
        HttpTransportSpy spy = new HttpTransportSpy(gateway);
        AnthropicMessagesChatModel model = new AnthropicMessagesChatModel(URL, "sk-test", MODEL, spy);

        List<ChatResponse> responses = model.stream(List.of(Msg.builder().role(MsgRole.USER).textContent("问").build()),
                null, GenerateOptions.builder().build()).collectList().block();

        assertThat(spy.headers).containsEntry("anthropic-beta", "thinking-binding-controls-2026-08-01");
        assertThat(responses.get(responses.size() - 1).getFinishReason()).isEqualTo("stop");
    }

    /**
     * 工具出错的结果要带 is_error，模型才知道这一步没拿到真实结果；用户拒绝确认卡的结果正文已说明，不算出错
     */
    @Test
    void shouldMarkOnlyErrorResultsAsError() {
        AnthropicMessagesChatModel model = new AnthropicMessagesChatModel(URL, "sk-test", MODEL,
                new GatewayReplay.ScriptedTransport());
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.USER).textContent("查三单").build(),
                Msg.builder().role(MsgRole.ASSISTANT).content(List.of(
                        ToolUseBlock.builder().id("a").name("query_order").input(Map.of()).build(),
                        ToolUseBlock.builder().id("b").name("query_order").input(Map.of()).build(),
                        ToolUseBlock.builder().id("c").name("cancel_order").input(Map.of()).build())).build(),
                toolResult("a", ToolResultState.ERROR),
                toolResult("b", ToolResultState.SUCCESS),
                toolResult("c", ToolResultState.DENIED));

        JSONArray results = JSONUtil.parseObj(model.requestBody(messages, null, GenerateOptions.builder().build(), true))
                .getJSONArray("messages").getJSONObject(2).getJSONArray("content");

        assertThat(results.getJSONObject(0).getBool("is_error")).isTrue();
        assertThat(results.getJSONObject(1).containsKey("is_error")).isFalse();
        assertThat(results.getJSONObject(2).containsKey("is_error")).isFalse();
    }

    private static Msg toolResult(String id, ToolResultState state) {
        return Msg.builder().role(MsgRole.TOOL).content(new ToolResultBlock(id, "query_order",
                List.of(TextBlock.builder().text("结果").build()), null, state)).build();
    }

    private static ChatResponse callOnce(AnthropicMessagesChatModel model) {
        return model.stream(List.of(Msg.builder().role(MsgRole.USER).textContent("只回复：好").build()), null,
                GenerateOptions.builder().stream(false).build()).blockLast();
    }

    private static JSONArray assistantTurns(String requestBody) {
        JSONArray turns = new JSONArray();
        JSONUtil.parseObj(requestBody).getJSONArray("messages").stream()
                .map(JSONObject.class::cast)
                .filter(turn -> "assistant".equals(turn.getStr("role")))
                .forEach(turns::add);
        return turns;
    }

    private static String recordedSignature() {
        return GatewayReplay.sse("anthropic-opus-step1.sse").stream()
                .map(JSONUtil::parseObj)
                .map(event -> event.getJSONObject("delta"))
                .filter(delta -> delta != null && "signature_delta".equals(delta.getStr("type")))
                .map(delta -> delta.getStr("signature"))
                .findFirst()
                .orElseThrow();
    }

    /**
     * 记下请求头，其余交给录音传输
     */
    private static final class HttpTransportSpy implements HttpTransport {

        private final HttpTransport delegate;
        private Map<String, String> headers;

        private HttpTransportSpy(HttpTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            headers = request.getHeaders();
            return delegate.execute(request);
        }

        @Override
        public Flux<String> stream(HttpRequest request) {
            headers = request.getHeaders();
            return delegate.stream(request);
        }

        @Override
        public void close() {
        }
    }
}

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
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gemini 经 ZenMux 的思考签名：用录下的真实流跑完整的 ReActAgent，签名得原样出现在后续请求的 reasoning_details 里
 * 网关只校验 reasoning_details 里的签名，放在 function.thought_signature 会被静默跳过校验
 */
class ZenMuxGeminiThoughtSignatureTransportTest {

    private static final String STEP1 = "gemini-hydrogen-step1.sse";
    private static final String STEP2 = "gemini-hydrogen-step2.sse";
    private static final String ANSWER = "gemini-hydrogen-answer.sse";

    @Test
    void shouldEchoEachStepSignatureInReasoningDetailsAcrossStateStore() {
        GatewayReplay.ScriptedTransport gateway = new GatewayReplay.ScriptedTransport()
                .thenStream(GatewayReplay.sse(STEP1))
                .thenStream(GatewayReplay.sse(STEP2))
                .thenStream(GatewayReplay.sse(ANSWER))
                .thenStream(GatewayReplay.sse(ANSWER));

        GatewayReplay.runTwoTurns(OpenAIChatModel.builder()
                .baseUrl("https://zenmux.ai")
                .endpointPath("/api/v1/chat/completions")
                .apiKey("sk-test")
                .modelName("google/gemini-4-hydrogen:google-vertex")
                .stream(true)
                .formatter(new ZenMuxFormatter())
                .httpTransport(new ZenMuxGeminiThoughtSignatureTransport(gateway))
                .build());

        assertThat(gateway.requests).hasSize(4);
        // 同一轮里的第二步
        assertThat(toolCallSteps(gateway.requests.get(1))).containsExactly(signatureOf(STEP1));
        // 第二轮只能从存下的 JSON 恢复，两步的签名都还得在
        assertThat(toolCallSteps(gateway.requests.get(3))).containsExactly(signatureOf(STEP1), signatureOf(STEP2));
        assertThat(gateway.requests.get(3)).doesNotContain("thought_signature");
    }

    /**
     * SDK 把第一个调用的签名复制给同批其余调用；Google 要求签名只留在原位
     */
    @Test
    void shouldKeepOneSignaturePerParallelBatch() {
        String body = """
                {"messages":[{"role":"assistant","tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"query_order","arguments":"{}","thought_signature":"SIG"}},
                  {"id":"call_2","type":"function","function":{"name":"query_order","arguments":"{}","thought_signature":"SIG"}}]}]}
                """;

        JSONObject message = JSONUtil.parseObj(ZenMuxGeminiThoughtSignatureTransport.moveSignaturesToDetails(
                HttpRequest.builder().url("u").method("POST").body(body).build()).getBody())
                .getJSONArray("messages").getJSONObject(0);

        assertThat(message.toString()).doesNotContain("thought_signature");
        JSONArray details = message.getJSONArray("reasoning_details");
        assertThat(details).hasSize(1);
        assertThat(details.getJSONObject(0).getStr("id")).isEqualTo("call_1");
        assertThat(details.getJSONObject(0).getStr("data")).isEqualTo("SIG");
        assertThat(details.getJSONObject(0).getStr("type")).isEqualTo("reasoning.encrypted");
    }

    /**
     * 消息里已有文本推理项但没有签名时，签名照样要补进去，原有项不能被覆盖
     */
    @Test
    void shouldAppendSignatureNextToExistingTextDetails() {
        String body = """
                {"messages":[{"role":"assistant","reasoning_details":[{"type":"reasoning.text","text":"先查订单"}],"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"query_order","arguments":"{}","thought_signature":"SIG"}}]}]}
                """;

        JSONObject message = JSONUtil.parseObj(ZenMuxGeminiThoughtSignatureTransport.moveSignaturesToDetails(
                HttpRequest.builder().url("u").method("POST").body(body).build()).getBody())
                .getJSONArray("messages").getJSONObject(0);

        JSONArray details = message.getJSONArray("reasoning_details");
        assertThat(details).hasSize(2);
        assertThat(details.getJSONObject(0).getStr("text")).isEqualTo("先查订单");
        assertThat(details.getJSONObject(1).getStr("type")).isEqualTo("reasoning.encrypted");
        assertThat(details.getJSONObject(1).getStr("data")).isEqualTo("SIG");
        assertThat(message.toString()).doesNotContain("thought_signature");
    }

    @Test
    void shouldLeaveOtherUpstreamsEncryptedReasoningUntouched() {
        Map<String, String> signatures = new HashMap<>();
        String details = "{\"choices\":[{\"delta\":{\"reasoning_details\":[{\"type\":\"reasoning.encrypted\","
                + "\"id\":\"call_1\",\"data\":\"X\",\"format\":\"openai-responses-v1\"}]}}]}";
        String call = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\"}]}}]}";

        assertThat(ZenMuxGeminiThoughtSignatureTransport.attachSignatures(details, signatures)).isEqualTo(details);
        assertThat(ZenMuxGeminiThoughtSignatureTransport.attachSignatures(call, signatures)).isEqualTo(call);
    }

    /**
     * 每个带工具调用的 assistant 消息各给出一份签名，按出现顺序
     */
    private static List<String> toolCallSteps(String requestBody) {
        return JSONUtil.parseObj(requestBody).getJSONArray("messages").stream()
                .map(JSONObject.class::cast)
                .filter(message -> message.containsKey("tool_calls"))
                .map(message -> message.getJSONArray("reasoning_details").getJSONObject(0).getStr("data"))
                .toList();
    }

    private static String signatureOf(String fixture) {
        return GatewayReplay.sse(fixture).stream()
                .map(JSONUtil::parseObj)
                .flatMap(chunk -> chunk.getJSONArray("choices").stream())
                .map(choice -> ((JSONObject) choice).getJSONObject("delta").getJSONArray("reasoning_details"))
                .filter(details -> details != null && !details.isEmpty())
                .map(details -> details.getJSONObject(0).getStr("data"))
                .findFirst()
                .orElseThrow();
    }
}

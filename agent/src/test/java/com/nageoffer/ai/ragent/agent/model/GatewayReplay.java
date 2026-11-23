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

import com.nageoffer.ai.ragent.agent.dao.mapper.AgentStateMapper;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把录下的网关报文喂给真实的 ReActAgent：回包走假传输，会话状态经 PgAgentStateStore 存成 JSON 再读回
 */
final class GatewayReplay {

    static final String USER_ID = "u-1001";
    static final String SESSION_ID = "c-2002";

    private GatewayReplay() {
    }

    /**
     * 跑两轮：第一轮跑完清掉框架的状态缓存，第二轮只能从存下的 JSON 恢复上下文，和线上每轮收尾一样
     */
    static void runTwoTurns(Model model) {
        ReActAgent agent = ReActAgent.builder()
                .name("replay")
                .sysPrompt("你是比特严选的售后客服助手")
                .model(model)
                .toolkit(toolkit())
                .maxIters(10)
                .maxRetries(1)
                .stateStore(new PgAgentStateStore(new MapBackedStateMapper()))
                .build();
        agent.call("帮我查一下订单 BT20261120001 的快递现在到哪了", context()).block();
        agent.clearStateCache(USER_ID, SESSION_ID);
        agent.call("谢谢", context()).block();
    }

    /**
     * 读 SSE 录音，去掉 data: 前缀、event: 行与 [DONE]，与 SDK 传输层交给上层的形状一致
     */
    static List<String> sse(String name) {
        List<String> data = new ArrayList<>();
        for (String line : resource(name).split("\n")) {
            if (line.startsWith("data:")) {
                String payload = line.substring("data:".length()).trim();
                if (!payload.isEmpty() && !"[DONE]".equals(payload)) {
                    data.add(payload);
                }
            }
        }
        return data;
    }

    static String resource(String name) {
        try (InputStream in = GatewayReplay.class.getResourceAsStream("/zenmux/" + name)) {
            if (in == null) {
                throw new IllegalStateException("缺少录音 " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RuntimeContext context() {
        return RuntimeContext.builder().userId(USER_ID).sessionId(SESSION_ID).build();
    }

    private static Toolkit toolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new BizTools());
        return toolkit;
    }

    /**
     * 录音时用的就是这两个工具，工具名与入参必须对得上
     */
    public static class BizTools {

        @Tool(name = "query_order", description = "按订单号查询订单，返回商品、状态和物流单号")
        public String queryOrder(@ToolParam(name = "order_no", description = "订单号") String orderNo) {
            return "{\"order_no\":\"" + orderNo + "\",\"item\":\"AirPods Pro 3\",\"status\":\"已发货\",\"logistics_no\":\"SF7788123456\"}";
        }

        @Tool(name = "query_logistics", description = "按物流单号查询物流轨迹，物流单号只能从订单查询结果里拿到")
        public String queryLogistics(@ToolParam(name = "logistics_no", description = "物流单号") String logisticsNo) {
            return "{\"logistics_no\":\"" + logisticsNo + "\",\"trace\":[\"11-22 08:40 派送中\"]}";
        }
    }

    /**
     * 按顺序吐预置回包，并记下每次真正发出的请求体
     */
    static final class ScriptedTransport implements HttpTransport {

        private final Deque<List<String>> streams = new ArrayDeque<>();
        private final Deque<String> bodies = new ArrayDeque<>();
        final List<String> requests = new ArrayList<>();

        ScriptedTransport thenStream(List<String> data) {
            streams.add(data);
            return this;
        }

        ScriptedTransport thenBody(String body) {
            bodies.add(body);
            return this;
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            requests.add(request.getBody());
            return HttpResponse.builder().statusCode(200).body(bodies.remove()).build();
        }

        @Override
        public Flux<String> stream(HttpRequest request) {
            requests.add(request.getBody());
            return Flux.fromIterable(streams.remove());
        }

        @Override
        public void close() {
        }
    }

    private static final class MapBackedStateMapper implements AgentStateMapper {

        private final Map<String, String> rows = new HashMap<>();

        @Override
        public void upsert(String userId, String sessionId, String stateKey, String payload) {
            rows.put(userId + "|" + sessionId + "|" + stateKey, payload);
        }

        @Override
        public String selectPayload(String userId, String sessionId, String stateKey) {
            return rows.get(userId + "|" + sessionId + "|" + stateKey);
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            return rows.keySet().stream().anyMatch(key -> key.startsWith(userId + "|" + sessionId + "|"));
        }

        @Override
        public void deleteBySession(String userId, String sessionId) {
            rows.keySet().removeIf(key -> key.startsWith(userId + "|" + sessionId + "|"));
        }

        @Override
        public void deleteByKey(String userId, String sessionId, String stateKey) {
            rows.remove(userId + "|" + sessionId + "|" + stateKey);
        }

        @Override
        public List<String> selectSessionIds(String userId) {
            return List.of(SESSION_ID);
        }
    }
}

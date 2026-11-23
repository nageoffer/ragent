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

package com.nageoffer.ai.ragent.agent.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按协议拼模型回包的流式事件：oa 开头是 OpenAI 兼容的 chunk，an 开头是 Anthropic Messages 的事件
 */
public final class Replies {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Replies() {
    }

    private static final AtomicInteger IDS = new AtomicInteger();

    public static String oaToolStart(int index, String id, String name, String arguments) {
        return oaChunk(Map.of("tool_calls", List.of(Map.of("index", index, "id", id, "type", "function",
                "function", Map.of("name", name, "arguments", arguments)))), null);
    }

    public static String oaArgs(int index, String arguments) {
        return oaChunk(Map.of("tool_calls", List.of(Map.of("index", index, "function", Map.of("arguments", arguments)))), null);
    }

    /**
     * 后续分片带着调用 id 但不带工具名，部分供应商这样发
     */
    public static String oaArgs(int index, String id, String arguments) {
        return oaChunk(Map.of("tool_calls", List.of(Map.of("index", index, "id", id, "function", Map.of("arguments", arguments)))), null);
    }

    public static String oaFinish(String reason) {
        return oaChunk(Map.of(), reason);
    }

    public static String oaRefusal(String text) {
        return oaChunk(Map.of("role", "assistant", "refusal", text), null);
    }

    public static List<String> openAiText(String text, String reason) {
        return List.of(oaChunk(Map.of("role", "assistant", "content", text), null), oaFinish(reason));
    }

    private static String oaChunk(Map<String, Object> delta, String finishReason) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        return json(Map.of("id", "chatcmpl-" + IDS.incrementAndGet(), "object", "chat.completion.chunk", "choices", List.of(choice)));
    }

    public static String anStart() {
        return json(Map.of("type", "message_start", "message", Map.of("id", "msg_" + IDS.incrementAndGet(),
                "usage", Map.of("input_tokens", 10, "output_tokens", 1))));
    }

    public static String anToolStart(int index, String id, String name) {
        return json(Map.of("type", "content_block_start", "index", index,
                "content_block", Map.of("type", "tool_use", "id", id, "name", name, "input", Map.of())));
    }

    public static String anToolStart(int index, String id, String name, Map<String, Object> input) {
        return json(Map.of("type", "content_block_start", "index", index,
                "content_block", Map.of("type", "tool_use", "id", id, "name", name, "input", input)));
    }

    public static String anArgs(int index, String part) {
        return json(Map.of("type", "content_block_delta", "index", index,
                "delta", Map.of("type", "input_json_delta", "partial_json", part)));
    }

    public static String anBlockStop(int index) {
        return json(Map.of("type", "content_block_stop", "index", index));
    }

    public static String anDelta(String stopReason) {
        return json(Map.of("type", "message_delta", "delta", Map.of("stop_reason", stopReason), "usage", Map.of("output_tokens", 5)));
    }

    public static String anStop() {
        return json(Map.of("type", "message_stop"));
    }

    public static List<String> anthropicText(String text, String stopReason) {
        return List.of(anStart(),
                json(Map.of("type", "content_block_start", "index", 0, "content_block", Map.of("type", "text", "text", ""))),
                json(Map.of("type", "content_block_delta", "index", 0, "delta", Map.of("type", "text_delta", "text", text))),
                anBlockStop(0), anDelta(stopReason), anStop());
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

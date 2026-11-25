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

package com.nageoffer.ai.ragent.infra.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Response;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.util.Set;

/**
 * 上游 HTTP 错误摘要，兼容 OpenAI 风格的 {"error":{...}} 与百炼原生接口的顶层 code
 * <p>
 * 只取错误码类字段，不取 message：供应商可能在 message 里回显请求原文，摘要会进日志和异常
 */
public record UpstreamError(int status, String code, String type, String param, String requestId) {

    /**
     * 百炼内容审查拒收：兼容模式与原生接口写法不同
     */
    private static final Set<String> CONTENT_REJECTED_CODES = Set.of("data_inspection_failed", "DataInspectionFailed");

    public static UpstreamError read(Response response) throws IOException {
        String body = response.body().string();
        return parse(response.code(), body, response.header("x-request-id"));
    }

    static UpstreamError parse(int status, String body, String headerRequestId) {
        JsonObject root = parseObject(body);
        JsonObject error = root.has("error") && root.get("error").isJsonObject() ? root.getAsJsonObject("error") : root;
        String requestId = text(root, "request_id");
        return new UpstreamError(status, text(error, "code"), text(error, "type"), text(error, "param"),
                requestId != null ? requestId : headerRequestId);
    }

    public ModelClientErrorType errorType() {
        return code != null && CONTENT_REJECTED_CODES.contains(code)
                ? ModelClientErrorType.CONTENT_REJECTED
                : ModelClientErrorType.fromHttpStatus(status);
    }

    @Override
    public @NonNull String toString() {
        return "HTTP " + status + " code=" + dash(code) + " type=" + dash(type) + " param=" + dash(param) + " request_id=" + dash(requestId);
    }

    private static JsonObject parseObject(String body) {
        try {
            JsonElement element = JsonParser.parseString(body);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            // 网关 HTML 错误页之类，只剩状态码可用
            return new JsonObject();
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}

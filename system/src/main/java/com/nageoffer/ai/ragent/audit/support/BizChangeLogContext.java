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

package com.nageoffer.ai.ragent.audit.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mzt.logapi.context.LogRecordContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Component
@RequiredArgsConstructor
public class BizChangeLogContext {

    private static final String BIZ_ID_VARIABLE = "bizChangeBizId";
    private static final String SNAPSHOT_VARIABLE = "bizChangeSnapshot";
    private static final String SKIP_VARIABLE = "bizChangeSkip";
    private static final String NAME_VARIABLE = "bizChangeName";

    public static final String BIZ_ID_EXPRESSION = "{{#" + BIZ_ID_VARIABLE + " != null ? #" + BIZ_ID_VARIABLE + " : 'UNKNOWN'}}";
    public static final String SNAPSHOT_EXPRESSION = "{{#" + SNAPSHOT_VARIABLE + "}}";
    public static final String RECORD_CONDITION = "{{#" + SKIP_VARIABLE + " == null || !" + "#" + SKIP_VARIABLE + "}}";

    private final ObjectMapper objectMapper;

    public void put(String bizId, Object beforeSnapshot, Object afterSnapshot) {
        LogRecordContext.putVariable(BIZ_ID_VARIABLE, bizId);
        LogRecordContext.putVariable(SNAPSHOT_VARIABLE, buildSnapshotPayload(beforeSnapshot, afterSnapshot));
    }

    public void skip() {
        LogRecordContext.putVariable(SKIP_VARIABLE, true);
    }

    // 写入业务对象名称，供操作描述引用（如文档名），避免描述里只有主键
    public void putName(String name) {
        LogRecordContext.putVariable(NAME_VARIABLE, name);
    }

    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化审计快照失败", e);
        }
    }

    private String buildSnapshotPayload(Object beforeSnapshot, Object afterSnapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        JsonNode beforeNode = toJsonNode(beforeSnapshot);
        JsonNode afterNode = toJsonNode(afterSnapshot);
        payload.put("beforeSnapshot", nullIfNullNode(beforeNode));
        payload.put("afterSnapshot", nullIfNullNode(afterNode));
        payload.put("changeDiff", diff(beforeNode, afterNode));
        return toJson(payload);
    }

    private JsonNode toJsonNode(Object value) {
        if (value == null) {
            return NullNode.getInstance();
        }
        return objectMapper.valueToTree(value);
    }

    private Object nullIfNullNode(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    private ArrayNode diff(JsonNode beforeNode, JsonNode afterNode) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        collectDiff("", beforeNode, afterNode, result);
        return result;
    }

    private void collectDiff(String path, JsonNode beforeNode, JsonNode afterNode, ArrayNode result) {
        JsonNode normalizedBefore = beforeNode == null ? NullNode.getInstance() : beforeNode;
        JsonNode normalizedAfter = afterNode == null ? NullNode.getInstance() : afterNode;
        if (Objects.equals(normalizedBefore, normalizedAfter)) {
            return;
        }

        if (normalizedBefore.isObject() && normalizedAfter.isObject()) {
            Set<String> fieldNames = new TreeSet<>();
            normalizedBefore.fieldNames().forEachRemaining(fieldNames::add);
            normalizedAfter.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                collectDiff(path + "/" + escapeJsonPointer(fieldName),
                        normalizedBefore.get(fieldName),
                        normalizedAfter.get(fieldName),
                        result);
            }
            return;
        }

        if (normalizedBefore.isArray() && normalizedAfter.isArray()) {
            int max = Math.max(normalizedBefore.size(), normalizedAfter.size());
            for (int i = 0; i < max; i++) {
                collectDiff(path + "/" + i,
                        i < normalizedBefore.size() ? normalizedBefore.get(i) : NullNode.getInstance(),
                        i < normalizedAfter.size() ? normalizedAfter.get(i) : NullNode.getInstance(),
                        result);
            }
            return;
        }

        ObjectNode item = JsonNodeFactory.instance.objectNode();
        item.put("field", path.isEmpty() ? "/" : path);
        item.set("before", normalizedBefore);
        item.set("after", normalizedAfter);
        result.add(item);
    }

    private String escapeJsonPointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }
}

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

package com.nageoffer.ai.ragent.ingestion.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class ConditionEvaluatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConditionEvaluator evaluator = new ConditionEvaluator(objectMapper);

    @Test
    void missingNestedFieldDoesNotMatchInclusiveCondition() {
        IngestionContext context = IngestionContext.builder()
                .metadata(Map.of())
                .build();
        ObjectNode rule = objectMapper.createObjectNode()
                .put("field", "metadata.score")
                .put("operator", "gte")
                .put("value", 10);

        assertFalse(evaluator.evaluate(context, rule));
    }

    @ParameterizedTest
    @MethodSource("invalidNumericOperands")
    void invalidNumericOperandsNeverMatch(String left, String operator, Object right) {
        IngestionContext context = IngestionContext.builder()
                .rawText(left)
                .build();

        assertFalse(evaluator.evaluate(context, numericRule(operator, right)));
    }

    static Stream<Arguments> invalidNumericOperands() {
        return Stream.of(
                arguments(null, "gt", 10),
                arguments(null, "gte", 10),
                arguments(null, "lt", 10),
                arguments(null, "lte", 10),
                arguments("not-a-number", "gt", 10),
                arguments("not-a-number", "gte", 10),
                arguments("not-a-number", "lt", 10),
                arguments("not-a-number", "lte", 10),
                arguments("10", "gt", "not-a-number"),
                arguments("10", "gte", "not-a-number"),
                arguments("10", "lt", "not-a-number"),
                arguments("10", "lte", "not-a-number"),
                arguments("10", "gt", null),
                arguments("10", "gte", null),
                arguments("10", "lt", null),
                arguments("10", "lte", null),
                arguments("NaN", "gt", 10),
                arguments("NaN", "gte", 10),
                arguments("NaN", "lt", 10),
                arguments("NaN", "lte", 10),
                arguments("Infinity", "gt", 10),
                arguments("Infinity", "gte", 10),
                arguments("Infinity", "lt", 10),
                arguments("Infinity", "lte", 10)
        );
    }

    @ParameterizedTest
    @CsvSource({
            "11, gt, 10, true",
            "10, gt, 10, false",
            "10, gte, 10, true",
            "9.5, gte, 10, false",
            "9.5, lt, 10, true",
            "10, lt, 10, false",
            "10, lte, 10, true",
            "11, lte, 10, false"
    })
    void validNumericOperandsKeepComparisonSemantics(
            String left, String operator, double right, boolean expected) {
        IngestionContext context = IngestionContext.builder()
                .rawText(left)
                .build();

        assertEquals(expected, evaluator.evaluate(context, numericRule(operator, right)));
    }

    private ObjectNode numericRule(String operator, Object right) {
        ObjectNode rule = objectMapper.createObjectNode()
                .put("field", "rawText")
                .put("operator", operator);
        rule.set("value", objectMapper.valueToTree(right));
        return rule;
    }

    @Test
    void unknownObjectConditionFailsClosed() {
        IngestionContext context = IngestionContext.builder().build();
        ObjectNode malformed = objectMapper.createObjectNode()
                .put("fields", "rawText")
                .put("operator", "eq")
                .put("value", "x");

        assertFalse(evaluator.evaluate(context, malformed));
    }

    @Test
    void nonArrayAnyFailsClosed() {
        IngestionContext context = IngestionContext.builder().build();
        ObjectNode nonArrayAny = objectMapper.createObjectNode();
        nonArrayAny.put("any", "not-an-array");
        assertFalse(evaluator.evaluate(context, nonArrayAny));

        ObjectNode emptyAny = objectMapper.createObjectNode();
        emptyAny.set("any", objectMapper.createArrayNode());
        assertFalse(evaluator.evaluate(context, emptyAny));
    }

    @Test
    void nonArrayAllFailsClosed() {
        IngestionContext context = IngestionContext.builder().build();
        ObjectNode nonArrayAll = objectMapper.createObjectNode();
        nonArrayAll.put("all", "not-an-array");
        assertFalse(evaluator.evaluate(context, nonArrayAll));
    }

    @Test
    void malformedConditionInsideNotFailsClosed() {
        IngestionContext context = IngestionContext.builder().build();

        ObjectNode notUnknown = objectMapper.createObjectNode();
        notUnknown.set("not", objectMapper.createObjectNode()
                .put("fields", "rawText").put("operator", "eq").put("value", "x"));
        assertFalse(evaluator.evaluate(context, notUnknown));

        ObjectNode notNonArrayAll = objectMapper.createObjectNode();
        notNonArrayAll.set("not", objectMapper.createObjectNode().put("all", "not-an-array"));
        assertFalse(evaluator.evaluate(context, notNonArrayAll));

        ObjectNode notNonArrayAny = objectMapper.createObjectNode();
        notNonArrayAny.set("not", objectMapper.createObjectNode().put("any", "not-an-array"));
        assertFalse(evaluator.evaluate(context, notNonArrayAny));
    }

    @Test
    void validNotConditionStillNegates() {
        IngestionContext context = IngestionContext.builder().build();

        ObjectNode notTrue = objectMapper.createObjectNode();
        notTrue.set("not", objectMapper.getNodeFactory().booleanNode(true));
        assertFalse(evaluator.evaluate(context, notTrue));

        ObjectNode notFalse = objectMapper.createObjectNode();
        notFalse.set("not", objectMapper.getNodeFactory().booleanNode(false));
        assertTrue(evaluator.evaluate(context, notFalse));
    }

    @Test
    void blankOrNullFieldFailsClosed() {
        IngestionContext context = IngestionContext.builder().build();

        ObjectNode blankField = objectMapper.createObjectNode()
                .put("field", "").put("operator", "eq").put("value", "x");
        assertFalse(evaluator.evaluate(context, blankField));

        ObjectNode nullField = objectMapper.createObjectNode()
                .put("operator", "eq").put("value", "x");
        nullField.putNull("field");
        assertFalse(evaluator.evaluate(context, nullField));
    }

    @Test
    void malformedNestedGroupInsideNotFailsClosed() {
        IngestionContext context = IngestionContext.builder().build();
        ObjectNode malformed = objectMapper.createObjectNode().put("fields", "rawText");

        ObjectNode any = objectMapper.createObjectNode();
        any.set("any", objectMapper.createArrayNode().add(true).add(malformed));
        ObjectNode notAny = objectMapper.createObjectNode();
        notAny.set("not", any);
        assertFalse(evaluator.evaluate(context, notAny));

        ObjectNode all = objectMapper.createObjectNode();
        all.set("all", objectMapper.createArrayNode().add(false).add(malformed));
        ObjectNode notAll = objectMapper.createObjectNode();
        notAll.set("not", all);
        assertFalse(evaluator.evaluate(context, notAll));
    }

    @Test
    void validGroupConditionsKeepShortCircuiting() {
        IngestionContext context = IngestionContext.builder().rawText("text").build();
        ObjectNode invalidRegex = objectMapper.createObjectNode()
                .put("field", "rawText")
                .put("operator", "regex")
                .put("value", "[");

        ObjectNode any = objectMapper.createObjectNode();
        any.set("any", objectMapper.createArrayNode().add(true).add(invalidRegex));
        assertTrue(evaluator.evaluate(context, any));

        ObjectNode all = objectMapper.createObjectNode();
        all.set("all", objectMapper.createArrayNode().add(false).add(invalidRegex));
        assertFalse(evaluator.evaluate(context, all));
    }
}

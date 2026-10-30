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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一字段、同一个取值，换操作符不应得出相反结论
 * <p>
 * {@code eq} 比较前会 trim 字符串（{@code normalize}），{@code in} 与 {@code contains} 漏了这一步，
 * 上游写进来的值只要带首尾空白（MIME 带尾随空格、文件名带空格、从表格里抓来的单元格文本），
 * 用 {@code eq} 配的规则命中、换成 {@code in} 配就不命中，且不报错、只表现为分支走了另一边
 */
class ConditionEvaluatorNormalizationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConditionEvaluator evaluator = new ConditionEvaluator(objectMapper);

    @Test
    void inReachesTheSameVerdictAsEqWhenValueHasSurroundingWhitespace() {
        IngestionContext context = IngestionContext.builder()
                .mimeType(" application/pdf ")
                .build();

        boolean eqVerdict = evaluator.evaluate(context, eqRule("mimeType", "application/pdf"));
        boolean inVerdict = evaluator.evaluate(context, inRule("mimeType", "application/pdf", "text/plain"));

        assertTrue(eqVerdict, "eq 已对字符串做 trim 归一化，应命中");
        assertEquals(eqVerdict, inVerdict, "in 与 eq 对同一个值应给出相同结论");
    }

    @Test
    void inMatchesElementWithOwnSurroundingWhitespace() {
        IngestionContext context = IngestionContext.builder()
                .mimeType("application/pdf")
                .build();

        // 条件值本身带空白（配置里复制粘贴带进来的空格），eq 能命中，in 也应能
        boolean eqVerdict = evaluator.evaluate(context, eqRule("mimeType", " application/pdf "));
        boolean inVerdict = evaluator.evaluate(context, inRule("mimeType", " application/pdf "));

        assertEquals(eqVerdict, inVerdict, "in 的元素同样应做 trim 归一化");
    }

    @Test
    void containsTrimsBothSides() {
        IngestionContext context = IngestionContext.builder()
                .mimeType("application/pdf")
                .build();

        ObjectNode rule = objectMapper.createObjectNode()
                .put("field", "mimeType")
                .put("operator", "contains")
                .put("value", " application/ ");

        assertTrue(evaluator.evaluate(context, rule), "contains 两侧都应 trim，否则带空格的子串永远命中不了");
    }

    @Test
    void inStillRejectsValueThatIsNotInTheList() {
        IngestionContext context = IngestionContext.builder()
                .mimeType("application/msword")
                .build();

        assertFalse(evaluator.evaluate(context, inRule("mimeType", "application/pdf", "text/plain")),
                "归一化不能让不匹配的值变成匹配");
    }

    @Test
    void inMatchesWhenFieldIsListAndScalarCarriesWhitespace() {
        IngestionContext context = IngestionContext.builder()
                .keywords(List.of("pdf", "docx"))
                .build();

        ObjectNode rule = objectMapper.createObjectNode()
                .put("field", "keywords")
                .put("operator", "in")
                .put("value", " docx ");

        assertTrue(evaluator.evaluate(context, rule), "字段是列表时标量同样应 trim 后再比");
    }

    private ObjectNode eqRule(String field, String value) {
        return objectMapper.createObjectNode()
                .put("field", field)
                .put("operator", "eq")
                .put("value", value);
    }

    private ObjectNode inRule(String field, String... values) {
        ObjectNode rule = objectMapper.createObjectNode()
                .put("field", field)
                .put("operator", "in");
        ArrayNode array = rule.putArray("value");
        for (String value : values) {
            array.add(value);
        }
        return rule;
    }
}

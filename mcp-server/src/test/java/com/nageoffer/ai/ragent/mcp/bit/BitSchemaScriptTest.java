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

package com.nageoffer.ai.ragent.mcp.bit;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitSchemaScriptTest {

    private static final List<String> TABLES = List.of("t_product", "t_product_sku", "t_order", "t_order_item",
            "t_logistics_trace", "t_after_sale", "t_ticket", "t_cart", "t_coupon", "t_user_coupon");

    private final String script = read();

    @Test
    void shouldCoverEveryBusinessTable() {
        TABLES.forEach(table -> assertTrue(script.contains("CREATE TABLE IF NOT EXISTS " + table + "\n"),
                "建表脚本缺少 " + table));
    }

    /**
     * 这个脚本在启动路径上，出现任何破坏性语句就等于「重启一次清空一次订单」
     * <p>
     * 清空是 initializer 那半的职责，靠 biz-cleanup.sql 在装数据集时跑
     */
    @Test
    void mustStayNonDestructive() {
        List.of("DROP TABLE", "DROP INDEX", "TRUNCATE", "DELETE FROM").forEach(keyword ->
                assertFalse(script.toUpperCase().contains(keyword), "建表脚本不允许出现 " + keyword));
    }

    /**
     * 版本表不在这个脚本里：它是决定这个脚本跑不跑的闸门，混进来就成了先有鸡还是先有蛋
     */
    @Test
    void versionTableMustNotBeDeclaredHere() {
        assertFalse(script.contains("t_schema_version"));
    }

    /**
     * 金额用浮点会让「满 300 减 50」在 299.999… 上判错，时间不带时区会在演示机与服务器之间差一天
     */
    @Test
    void mustNotUseTypesThatBreakMoneyAndTimeComparison() {
        List.of("DOUBLE", "REAL", "FLOAT", "TIMESTAMP ", "TIMESTAMP\n").forEach(type ->
                assertFalse(script.toUpperCase().contains(type), "建表脚本不允许出现 " + type.strip()));
    }

    private String read() {
        try {
            return new String(new ClassPathResource("db/bit-schema.sql").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不到建表脚本", e);
        }
    }
}

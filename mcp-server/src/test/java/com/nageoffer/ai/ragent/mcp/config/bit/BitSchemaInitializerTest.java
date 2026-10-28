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

package com.nageoffer.ai.ragent.mcp.config.bit;

import com.nageoffer.ai.ragent.mcp.config.bit.BitSchemaInitializer.ParsedUrl;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitSchemaInitializerTest {

    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/ragent_bit?client_encoding=UTF8";

    @Test
    void shouldTakeDatabaseNameOutOfUrl() {
        ParsedUrl parsed = BitSchemaInitializer.parseUrl(URL);

        assertEquals("ragent_bit", parsed.database());
        assertEquals("127.0.0.1:5432", parsed.authority());
        assertEquals(URL, parsed.url());
    }

    /**
     * 换维护库时参数要原样带着：client_encoding 掉了，建库连接的编码就跟业务连接不是一套
     */
    @Test
    void shouldKeepQueryWhenSwitchingToMaintenanceDatabase() {
        assertEquals("jdbc:postgresql://127.0.0.1:5432/postgres?client_encoding=UTF8",
                BitSchemaInitializer.parseUrl(URL).withDatabase("postgres"));
    }

    @Test
    void shouldWorkWithoutPortAndQuery() {
        ParsedUrl parsed = BitSchemaInitializer.parseUrl("jdbc:postgresql://db-host/ragent_bit");

        assertEquals("ragent_bit", parsed.database());
        assertEquals("jdbc:postgresql://db-host/postgres", parsed.withDatabase("postgres"));
    }

    /**
     * 库名要拼进 CREATE DATABASE，DDL 用不了占位符，所以只能在进去之前卡死取值范围
     */
    @Test
    void shouldRejectDatabaseNameThatCannotBeInterpolatedSafely() {
        assertThrows(IllegalStateException.class,
                () -> BitSchemaInitializer.parseUrl("jdbc:postgresql://127.0.0.1:5432/bit\";DROP DATABASE ragent"));
        assertThrows(IllegalStateException.class,
                () -> BitSchemaInitializer.parseUrl("jdbc:postgresql://127.0.0.1:5432/"));
    }

    @Test
    void shouldRejectUrlOfAnotherDatabaseVendor() {
        assertThrows(IllegalStateException.class,
                () -> BitSchemaInitializer.parseUrl("jdbc:mysql://127.0.0.1:3306/ragent_bit"));
        assertThrows(IllegalStateException.class,
                () -> BitSchemaInitializer.parseUrl(null));
    }

    /**
     * 建库权限和连不上是两件事，给的下一步也不一样，不能笼统报「初始化失败」
     */
    @Test
    void shouldTranslateFailureIntoSomethingActionable() {
        ParsedUrl parsed = BitSchemaInitializer.parseUrl(URL);

        assertTrue(BitSchemaInitializer.explain(new SQLException("denied", "42501"), parsed)
                .contains("createdb ragent_bit"));
        String unreachable = BitSchemaInitializer.explain(new SQLException("refused", "08001"), parsed);
        assertTrue(unreachable.contains("ragent.bit.datasource"));
        assertTrue(unreachable.contains(URL));
    }

    /**
     * 未知版本不能引导删库，避免丢失业务回执
     */
    @Test
    void versionMismatchMustTellHowToRecover() {
        String message = BitSchemaInitializer.versionMismatch(0, "ragent_bit");

        assertTrue(message.contains("不要删除业务库"));
        assertTrue(message.contains("ragent_bit"));
        assertTrue(message.contains("迁移脚本"));
    }
}

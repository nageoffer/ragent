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

package com.nageoffer.ai.ragent.rag.core.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GraphFileSourceTest {

    @Test
    @DisplayName("编码与解析互为逆运算")
    void roundTrip() {
        String filePath = GraphFileSource.encode("kb-finance", "1954071234567890123");

        GraphFileSource source = GraphFileSource.parse(filePath);

        assertEquals("kb-finance", source.collectionName());
        assertEquals("1954071234567890123", source.docId());
    }

    @Test
    @DisplayName("互为前缀的库名不互相误归属")
    void prefixOverlappingCollectionsDoNotCrossMatch() {
        // 回归：kb 与 kb_hr 合法共存，旧 contains(collectionName + "_") 会把 kb_hr 的文档归给 kb，
        // 读侧串证据、删侧连带删光 kb_hr 的图谱数据
        GraphFileSource source = GraphFileSource.parse("kb_hr_1954071234567890123");

        assertEquals("kb_hr", source.collectionName());
        assertEquals("1954071234567890123", source.docId());
    }

    @Test
    @DisplayName("库名可含下划线与数字段，从右锚定仍唯一还原")
    void underscoreAndDigitSegmentsInCollectionName() {
        assertEquals(new GraphFileSource("kb_2024_v2", "98765"),
                GraphFileSource.parse(GraphFileSource.encode("kb_2024_v2", "98765")));
        // 库名以数字段结尾时，末段数字才是 docId
        assertEquals(new GraphFileSource("kb_7", "123"),
                GraphFileSource.parse("kb_7_123"));
    }

    @Test
    @DisplayName("容忍服务端的目录前缀与扩展名修饰")
    void toleratesServerSideDecorations() {
        assertEquals(new GraphFileSource("kb", "123"),
                GraphFileSource.parse("/data/inputs/kb_123.txt"));
        assertEquals(new GraphFileSource("kb", "123"),
                GraphFileSource.parse("kb_123.tar.gz"));
    }

    @Test
    @DisplayName("不符合编码的 file_path 一律解析为 null")
    void nonConformingPathsParseToNull() {
        assertNull(GraphFileSource.parse(null));
        assertNull(GraphFileSource.parse("  "));
        assertNull(GraphFileSource.parse("readme.txt"));
        assertNull(GraphFileSource.parse("kb_"));
        assertNull(GraphFileSource.parse("123"));
        // 数字不在末段：不能退化为「取任意数字串」，否则乱归属比不归属更糟
        assertNull(GraphFileSource.parse("abc123def"));
    }
}

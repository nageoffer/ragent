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

package com.nageoffer.ai.ragent.knowledge.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.ingest.IngestionSpec;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 摄取配置读写：重点盯"整篇不分块"的往返
 * <p>
 * 这条链路上哨兵有两种表示——线路上的 {@code -1} 与领域内部的 {@code Integer.MAX_VALUE}，翻译只允许
 * 发生在 codec 内部；漏一个方向，症状不是报错而是界面上出现 2147483647
 */
class IngestionSpecCodecTest {

    private final IngestionSpecCodec codec = new IngestionSpecCodec(new ObjectMapper());

    @Test
    void wholeDocumentSurvivesSubmitStoreReloadRoundTrip() {
        String stored = codec.normalize(
                "{\"parseProfile\":\"fidelity\",\"maxChars\":-1,\"overlapChars\":128,\"rowsPerChunk\":50}");

        // 落库形状必须还是 -1：出参原样送回前端，前端只认这个值
        assertTrue(stored.contains("\"maxChars\":-1"), stored);
        assertFalse(stored.contains(String.valueOf(Integer.MAX_VALUE)), stored);

        IngestionSpec reloaded = codec.read(stored);
        assertTrue(reloaded.budget().isWholeDocument());
        assertEquals(ParseProfile.FIDELITY, reloaded.parseProfile());
    }

    @Test
    void legacyMaxValueStillReadsAsWholeDocument() {
        // 旧构建把领域内部哨兵直接序列化进了库
        String legacy = "{\"version\":2,\"parseProfile\":\"fast\","
                + "\"budget\":{\"maxChars\":2147483647,\"overlapChars\":0,\"rowsPerChunk\":2147483647}}";

        assertTrue(codec.read(legacy).budget().isWholeDocument());
        // 旧行不刷库也能回显正确：出参那一层重写一遍即收敛到线路哨兵
        assertTrue(codec.write(codec.read(legacy)).contains("\"maxChars\":-1"));
    }

    @Test
    void budgetRoundTripsAndMissingFieldsFallBackToDefaults() {
        IngestionSpec spec = codec.read(codec.normalize("{\"maxChars\":600}"));

        ChunkBudget defaults = ChunkBudget.defaults();
        assertEquals(600, spec.budget().maxChars());
        // 缺省重叠跟着块大小等比走，而不是照搬默认预算里配 1024 的那个 128
        assertEquals(75, spec.budget().overlapChars());
        assertEquals(defaults.rowsPerChunk(), spec.budget().rowsPerChunk());
        assertEquals(ParseProfile.FAST, spec.parseProfile());
    }

    @Test
    void illegalOverlapIsRejectedRatherThanSilentlyFixed() {
        assertThrows(ClientException.class,
                () -> codec.normalize("{\"maxChars\":500,\"overlapChars\":500}"));
    }

    /**
     * 上限是对外公布过的（schema 下发给前端），就得真的拦
     * <p>
     * 只当提示数字的话，十万字的块能一路落库、到嵌入那步才炸；整篇不分块豁免这两条上限，由
     * {@link #wholeDocumentSurvivesSubmitStoreReloadRoundTrip} 覆盖
     */
    @Test
    void overLimitBudgetIsRejected() {
        assertThrows(ClientException.class,
                () -> codec.normalize("{\"maxChars\":" + (ChunkBudget.MAX_CHARS_LIMIT + 1) + "}"));
        assertThrows(ClientException.class,
                () -> codec.normalize("{\"rowsPerChunk\":" + (ChunkBudget.ROWS_PER_CHUNK_LIMIT + 1) + "}"));
    }
}

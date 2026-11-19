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

package com.nageoffer.ai.ragent.core.chunk.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkAssemblerTest {

    @Test
    @DisplayName("从三级标题起头的块，向量文本只补上面两级，展示文本原样")
    void embeddingTextGetsOnlyMissingOutlineLevels() {
        ChunkMetadata metadata = ChunkMetadata.builder()
                .outlinePath(List.of("第一章 总则", "1.2 适用范围", "1.2.1 外包人员"))
                .build();

        Chunk chunk = ChunkAssembler.assemble(0, ChunkDraft.of("### 1.2.1 外包人员\n参照正式员工执行", metadata));

        assertEquals("### 1.2.1 外包人员\n参照正式员工执行", chunk.content());
        assertEquals("第一章 总则 / 1.2 适用范围\n### 1.2.1 外包人员\n参照正式员工执行", chunk.embeddingText());
    }
}

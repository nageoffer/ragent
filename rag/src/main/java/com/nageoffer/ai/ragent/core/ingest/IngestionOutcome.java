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

package com.nageoffer.ai.ragent.core.ingest;

import com.nageoffer.ai.ragent.core.chunk.model.Chunk;

import java.util.List;

/**
 * 摄取结果：块数、耗时、命中的解析器，够外层写摄取日志与更新统计
 * <p>
 * 只到 {@link Chunk} 为止，向量已由内核写进各索引后端，不再随结果传出一份
 *
 * @param mimeType   识别出的真实 MIME
 * @param parserType 实际命中的解析器类型
 * @param blockCount 解析产出的 Block 数量
 * @param chunks     最终落库的块
 * @param timings    各阶段耗时
 */
public record IngestionOutcome(
        String mimeType,
        String parserType,
        int blockCount,
        List<Chunk> chunks,
        IngestionTimings timings
) {

    public IngestionOutcome {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        timings = timings == null ? IngestionTimings.zero() : timings;
    }

    public int chunkCount() {
        return chunks.size();
    }

    /**
     * 各阶段耗时（毫秒）：解析含类型识别，分块含 Block / Chunk 两层插槽加工
     */
    public record IngestionTimings(long parseMillis, long chunkMillis, long embedMillis, long indexMillis) {

        public static IngestionTimings zero() {
            return new IngestionTimings(0, 0, 0, 0);
        }
    }
}

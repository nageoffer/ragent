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

import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;

/**
 * 文档级摄取配置（L3）：这一篇怎么解析、怎么切，对应 {@code t_knowledge_document.ingestion_spec} 一个 JSONB 列
 * <p>
 * 不含 embeddingModel：嵌入模型是知识库级（L2）约束性配置，文档级无权覆盖，只能由 {@link VectorTarget} 提供
 *
 * @param version      结构版本，用于未来演进时识别旧值
 * @param parseProfile 解析档位
 * @param budget       分块预算
 */
public record IngestionSpec(int version, ParseProfile parseProfile, ChunkBudget budget) {

    public static final int CURRENT_VERSION = 2;

    public IngestionSpec {
        if (version <= 0) {
            throw new IllegalArgumentException("version 必须 > 0，实际 " + version);
        }
        parseProfile = parseProfile == null ? ParseProfile.defaultProfile() : parseProfile;
        budget = budget == null ? ChunkBudget.defaults() : budget;
    }

    public static IngestionSpec defaults() {
        return new IngestionSpec(CURRENT_VERSION, ParseProfile.defaultProfile(), ChunkBudget.defaults());
    }

    public static IngestionSpec of(ParseProfile parseProfile, ChunkBudget budget) {
        return new IngestionSpec(CURRENT_VERSION, parseProfile, budget);
    }
}

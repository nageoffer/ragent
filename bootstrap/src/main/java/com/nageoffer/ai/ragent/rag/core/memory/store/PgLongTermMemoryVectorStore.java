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

package com.nageoffer.ai.ragent.rag.core.memory.store;

import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 pgvector 的长期记忆向量存储。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgLongTermMemoryVectorStore implements LongTermMemoryVectorStore {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;

    @Override
    public void upsert(String memoryId, String userId, String content, String embeddingModel) {
        float[] vector = toArray(embeddingModel == null || embeddingModel.isBlank()
                ? embeddingService.embed(content)
                : embeddingService.embed(content, embeddingModel));
        jdbcTemplate.update(
                "INSERT INTO t_long_term_memory_vector (id, user_id, content, embedding) VALUES (?, ?, ?, ?::vector) " +
                        "ON CONFLICT (id) DO UPDATE SET user_id = EXCLUDED.user_id, content = EXCLUDED.content, embedding = EXCLUDED.embedding, update_time = CURRENT_TIMESTAMP",
                memoryId, userId, content, toVectorLiteral(vector)
        );
    }

    @Override
    public List<String> search(String userId, String query, int topK) {
        float[] vector = toArray(embeddingService.embed(query));
        String vectorLiteral = toVectorLiteral(vector);
        return jdbcTemplate.query(
                "SELECT id FROM t_long_term_memory_vector WHERE user_id = ? ORDER BY embedding <=> ?::vector LIMIT ?",
                (rs, rowNum) -> rs.getString("id"),
                userId, vectorLiteral, topK
        );
    }

    private float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }
}

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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorStoreAdmin implements VectorStoreAdmin {


    private final JdbcTemplate jdbcTemplate;
    private final RAGDefaultProperties ragDefaultProperties;

    @Override
    public void ensureVectorSpace(VectorSpaceSpec spec) {
        String indexName = "idx_kv_embedding_hnsw";

        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE indexname = ?", Integer.class, indexName);

        if (count != null && count > 0) {
            log.debug("HNSW索引已存在: {}", indexName);
            return;
        }

        int dimension = ragDefaultProperties.getDimension();
        log.info("创建pgvector HNSW索引，维度: {}", dimension);
        jdbcTemplate.execute(String.format("CREATE INDEX IF NOT EXISTS %s ON t_knowledge_vector USING hnsw (embedding vector_cosine_ops)", indexName));
    }

    @Override
    public boolean vectorSpaceExists(VectorSpaceId spaceId) {
        try {
            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_knowledge_vector LIMIT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void dropVectorSpace(String collectionName) {
        // PG 为共享表：仅删除该 collection 的残留向量行，不动共享 HNSW 索引
        // 常规情况下文档删除已逐一清理，此处多为 0 行的兜底
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        int deleted = jdbcTemplate.update("DELETE FROM t_knowledge_vector WHERE collection_name = ?", collectionName);
        log.info("已删除 collection={} 的残留向量行，count={}", collectionName, deleted);
    }
}

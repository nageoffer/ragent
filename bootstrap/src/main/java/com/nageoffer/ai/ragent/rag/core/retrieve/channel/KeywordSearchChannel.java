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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 关键词全文检索通道
 * 使用 PostgreSQL tsvector/tsquery 进行关键词精确匹配检索，
 * 弥补向量检索对专有名词、产品型号等精确关键词的召回不足。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class KeywordSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final JdbcTemplate jdbcTemplate;
    private final Executor innerRetrievalExecutor;

    public KeywordSearchChannel(SearchChannelProperties properties,
                                KnowledgeBaseMapper knowledgeBaseMapper,
                                JdbcTemplate jdbcTemplate,
                                Executor innerRetrievalExecutor) {
        this.properties = properties;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.innerRetrievalExecutor = innerRetrievalExecutor;
    }

    @Override
    public String getName() {
        return "KeywordSearch";
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getKeyword().isEnabled()
                && context.getMainQuestion() != null
                && !context.getMainQuestion().isBlank();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            String query = context.getMainQuestion();
            log.info("执行关键词检索，问题：{}", query);

            List<String> collections = getAllKBCollections();
            if (collections.isEmpty()) {
                log.warn("未找到任何 KB collection，跳过关键词检索");
                return emptyResult(startTime);
            }

            int topK = context.getTopK() * properties.getChannels().getKeyword().getTopKMultiplier();
            List<RetrievedChunk> allChunks = retrieveFromAllCollections(query, collections, topK);

            long latency = System.currentTimeMillis() - startTime;
            log.info("关键词检索完成，检索到 {} 个 Chunk，耗时 {}ms", allChunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD_ES)
                    .channelName(getName())
                    .chunks(allChunks)
                    .latencyMs(latency)
                    .build();

        } catch (Exception e) {
            log.error("关键词检索失败", e);
            return emptyResult(startTime);
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.KEYWORD_ES;
    }

    private List<String> getAllKBCollections() {
        Set<String> collections = new HashSet<>();
        List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .select(KnowledgeBaseDO::getCollectionName)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        for (KnowledgeBaseDO kb : kbList) {
            String name = kb.getCollectionName();
            if (name != null && !name.isBlank()) {
                collections.add(name);
            }
        }
        return new ArrayList<>(collections);
    }

    private List<RetrievedChunk> retrieveFromAllCollections(String query,
                                                            List<String> collections,
                                                            int topK) {
        List<CompletableFuture<List<RetrievedChunk>>> futures = collections.stream()
                .map(collection -> CompletableFuture.supplyAsync(
                        () -> searchInCollection(query, collection, topK),
                        innerRetrievalExecutor
                ))
                .toList();

        List<RetrievedChunk> allChunks = new ArrayList<>();
        int success = 0;
        int failure = 0;
        for (int i = 0; i < futures.size(); i++) {
            try {
                List<RetrievedChunk> chunks = futures.get(i).join();
                allChunks.addAll(chunks);
                success++;
            } catch (Exception e) {
                failure++;
                log.error("关键词检索失败 - Collection: {}", collections.get(i), e);
            }
        }
        log.info("关键词检索统计 - 总 collection 数: {}, 成功: {}, 失败: {}, Chunk 总数: {}",
                collections.size(), success, failure, allChunks.size());
        return allChunks;
    }

    private List<RetrievedChunk> searchInCollection(String query, String collectionName, int topK) {
        // 预处理查询：移除特殊字符避免 tsquery 解析错误
        String sanitized = query.replaceAll("[^\\w\\u4e00-\\u9fff\\s]", " ");
        if (sanitized.isBlank()) {
            return List.of();
        }
        // plainto_tsquery 将输入切分为词后用 & 连接，sanitized 中的空格天然实现 AND 语义
        // 使用带索引的 tsv 列而非实时计算 to_tsvector
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        return jdbcTemplate.query(
                "SELECT id, content, ts_rank(tsv, plainto_tsquery('simple', ?)) AS score " +
                "FROM t_knowledge_vector " +
                "WHERE metadata->>'collection_name' = ? AND tsv @@ plainto_tsquery('simple', ?) " +
                "ORDER BY score DESC LIMIT ?",
                (rs, rowNum) -> RetrievedChunk.builder()
                        .id(rs.getString("id"))
                        .text(rs.getString("content"))
                        .score(rs.getFloat("score"))
                        .build(),
                sanitized, collectionName, sanitized, topK
        );
    }

    private SearchChannelResult emptyResult(long startTime) {
        return SearchChannelResult.builder()
                .channelType(SearchChannelType.KEYWORD_ES)
                .channelName(getName())
                .chunks(List.of())
                .latencyMs(System.currentTimeMillis() - startTime)
                .build();
    }
}

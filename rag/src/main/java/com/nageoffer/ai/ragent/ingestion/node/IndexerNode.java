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

package com.nageoffer.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.IndexerSettings;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceSpec;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引节点：把已向量化的块写入向量存储
 * <p>
 * 直接写块，元数据统一走块自己的序列化点，管道注入的字段（任务 ID、来源等）落进元数据扩展位；维度
 * 校验不在此重复，向量化阶段已按部署级维度逐条校验，块类型本身也保证向量非空
 */
@Slf4j
@Component
public class IndexerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final VectorStoreService vectorStoreService;
    private final RAGDefaultProperties ragDefaultProperties;

    public IndexerNode(ObjectMapper objectMapper,
                       VectorStoreAdmin vectorStoreAdmin,
                       VectorStoreService vectorStoreService,
                       RAGDefaultProperties ragDefaultProperties) {
        this.objectMapper = objectMapper;
        this.vectorStoreAdmin = vectorStoreAdmin;
        this.vectorStoreService = vectorStoreService;
        this.ragDefaultProperties = ragDefaultProperties;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.INDEXER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        List<EmbeddedChunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.fail(new ClientException("没有可索引的分块"));
        }
        IndexerSettings settings = parseSettings(config.getSettings());
        String partition = resolvePartition(context);
        if (!StringUtils.hasText(partition)) {
            return NodeResult.fail(new ClientException("索引器需要指定集合名称"));
        }

        List<EmbeddedChunk> enriched = attachPipelineMetadata(context, chunks, settings.getMetadataFields());
        context.setChunks(enriched);

        if (context.isSkipIndexerWrite()) {
            // 调用方会在事务中统一写向量，此处只做准备
            return NodeResult.ok("已准备 " + enriched.size() + " 个分块（向量写入由调用方统一完成）");
        }

        ensureVectorSpace(partition);
        vectorStoreService.indexDocumentChunks(partition, context.getTaskId(), enriched);
        log.info("向量写入成功，集合={}，行数={}", partition, enriched.size());
        return NodeResult.ok("已写入 " + enriched.size() + " 个分块到集合 " + partition);
    }

    private IndexerSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return IndexerSettings.builder().build();
        }
        return objectMapper.convertValue(node, IndexerSettings.class);
    }

    private String resolvePartition(IngestionContext context) {
        if (context.getVectorTarget() != null) {
            return context.getVectorTarget().partition();
        }
        if (context.getVectorSpaceId() != null && StringUtils.hasText(context.getVectorSpaceId().getLogicalName())) {
            return context.getVectorSpaceId().getLogicalName();
        }
        return ragDefaultProperties.getCollectionName();
    }

    /**
     * 把管道级信息写进块元数据的扩展位
     * <p>
     * {@code metadataFields} 为空时注入全部管道级字段；配置了白名单则只注入命中的字段
     */
    private List<EmbeddedChunk> attachPipelineMetadata(IngestionContext context,
                                                       List<EmbeddedChunk> chunks,
                                                       List<String> metadataFields) {
        Map<String, Object> pipelineMetadata = new LinkedHashMap<>();
        putIfPresent(pipelineMetadata, "task_id", context.getTaskId());
        putIfPresent(pipelineMetadata, "pipeline_id", context.getPipelineId());
        DocumentSource source = context.getSource();
        if (source != null) {
            if (source.getType() != null) {
                pipelineMetadata.put("source_type", source.getType().getValue());
            }
            putIfPresent(pipelineMetadata, "source_location", source.getLocation());
        }
        if (context.getMetadata() != null) {
            pipelineMetadata.putAll(context.getMetadata());
        }
        if (metadataFields != null && !metadataFields.isEmpty()) {
            pipelineMetadata.keySet().retainAll(metadataFields);
        }
        if (pipelineMetadata.isEmpty()) {
            return chunks;
        }

        List<EmbeddedChunk> result = new ArrayList<>(chunks.size());
        for (EmbeddedChunk chunk : chunks) {
            result.add(new EmbeddedChunk(
                    chunk.chunk().withMetadata(chunk.metadata().withExtras(pipelineMetadata)),
                    chunk.embedding()));
        }
        return result;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (StringUtils.hasText(value)) {
            map.put(key, value);
        }
    }

    private void ensureVectorSpace(String partition) {
        VectorSpaceId spaceId = VectorSpaceId.builder().logicalName(partition).build();
        if (vectorStoreAdmin.vectorSpaceExists(spaceId)) {
            return;
        }
        vectorStoreAdmin.ensureVectorSpace(VectorSpaceSpec.builder()
                .spaceId(spaceId)
                .remark("RAG向量存储空间")
                .build());
    }
}

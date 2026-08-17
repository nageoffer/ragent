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

package com.nageoffer.ai.ragent.ingestion.engine;

import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 节点输出提取器：从 {@code IngestionContext} 里取各节点的输出摘要
 * <p>
 * 只产摘要不产实体：输出落在 {@code t_ingestion_task_node.output_json}，一个 1MB 截断、纯诊断用途的
 * TEXT 列，塞实体（源文件字节 / 全量 Block / 带向量的块）必然顶穿阈值，截断后剩一段解析不了的残缺
 * JSON；实体各有正本，源文件在对象存储、块在 {@code t_knowledge_chunk} 与向量库
 */
@Component
public class NodeOutputExtractor {

    public Map<String, Object> extract(IngestionContext context, NodeConfig config) {
        if (context == null || config == null) {
            return Map.of();
        }
        IngestionNodeType nodeType = resolveNodeType(config.getNodeType());
        if (nodeType == null) {
            return genericOutput(context);
        }
        return switch (nodeType) {
            case FETCHER -> fetcherOutput(context);
            case PARSER -> parserOutput(context);
            case ENHANCER -> enhancerOutput(context);
            case CHUNKER -> chunkerOutput(context);
            case ENRICHER -> enricherOutput(context);
            case INDEXER -> indexerOutput(context, config);
        };
    }

    private Map<String, Object> fetcherOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        DocumentSource source = context.getSource();
        if (source != null) {
            Map<String, Object> sourceView = new LinkedHashMap<>();
            sourceView.put("type", source.getType() == null ? null : source.getType().getValue());
            sourceView.put("location", source.getLocation());
            sourceView.put("fileName", source.getFileName());
            output.put("source", sourceView);
        }
        output.put("mimeType", context.getMimeType());
        byte[] raw = context.getRawBytes();
        if (raw != null) {
            // 只记长度不记内容：源文件 base64 约 1.33 倍体积，超过 ~750KB 就把这条记录撑到截断
            output.put("rawBytesLength", raw.length);
        }
        return output;
    }

    /**
     * 解析节点输出：只给块结构摘要，不给全量 Block
     * <p>
     * 正文看 {@code rawText} 就够，此处只回答"切出了什么"——连 Block 一起塞等于把正文在同一条记录里
     * 存两遍，稍大的文档就顶到 output_json 的 1MB 截断线
     */
    private Map<String, Object> parserOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mimeType", context.getMimeType());
        output.put("rawText", context.getRawText());
        List<Block> blocks = context.getDocument() == null || context.getDocument().getBlocks() == null
                ? List.of()
                : context.getDocument().getBlocks();
        output.put("blockCount", blocks.size());
        output.put("blockTypes", countByType(blocks));
        return output;
    }

    /**
     * 按 Block 具体类型计数，如 {@code {ParagraphBlock=12, TableBlock=1}}
     */
    private static Map<String, Long> countByType(List<Block> blocks) {
        return blocks.stream().collect(Collectors.groupingBy(
                block -> block.getClass().getSimpleName(),
                LinkedHashMap::new,
                Collectors.counting()));
    }

    private Map<String, Object> enhancerOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("enhancedText", context.getEnhancedText());
        output.put("keywords", context.getKeywords());
        output.put("questions", context.getQuestions());
        output.put("metadata", context.getMetadata());
        return output;
    }

    private Map<String, Object> chunkerOutput(IngestionContext context) {
        return chunkSummary(safeChunks(context));
    }

    /**
     * 加工节点额外给出扩展位键名：加工唯一改动的就是 extras，
     * 不列出来的话这份输出与分块节点逐字相同，看不出加工到底跑没跑
     */
    private Map<String, Object> enricherOutput(IngestionContext context) {
        List<EmbeddedChunk> chunks = safeChunks(context);
        Map<String, Object> output = chunkSummary(chunks);
        output.put("extraKeys", collectExtraKeys(chunks));
        return output;
    }

    private Map<String, Object> indexerOutput(IngestionContext context, NodeConfig config) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("settings", config.getSettings());
        output.putAll(chunkSummary(safeChunks(context)));
        return output;
    }

    /**
     * 分块 / 加工 / 索引三节点共用的块摘要
     * <p>
     * 不放整块：{@link EmbeddedChunk} 带着完整 embedding 数组，1536 维一块序列化约 15KB，上百个块必然
     * 顶穿 1MB 截断线，何况同一份块要被三个节点各存一遍；{@code embeddingDim} 值得留，它是"同一分区
     * 混进两种语义空间的向量"这类事故的现场证据
     */
    private static Map<String, Object> chunkSummary(List<EmbeddedChunk> chunks) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("chunkCount", chunks.size());
        summary.put("totalChars", chunks.stream()
                .mapToInt(chunk -> chunk.content() == null ? 0 : chunk.content().length())
                .sum());
        summary.put("embeddingDim", chunks.isEmpty() ? 0 : chunks.get(0).dimension());
        return summary;
    }

    private static Set<String> collectExtraKeys(List<EmbeddedChunk> chunks) {
        return chunks.stream()
                .flatMap(chunk -> chunk.metadata().extras().keySet().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 提取器在节点失败分支上也会被调用，且那次调用不在任何 try 里——这里宁可多一次判空
     */
    private static List<EmbeddedChunk> safeChunks(IngestionContext context) {
        if (context.getChunks() == null) {
            return List.of();
        }
        return context.getChunks().stream().filter(Objects::nonNull).toList();
    }

    private Map<String, Object> genericOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mimeType", context.getMimeType());
        output.put("rawText", context.getRawText());
        output.put("enhancedText", context.getEnhancedText());
        output.put("keywords", context.getKeywords());
        output.put("questions", context.getQuestions());
        output.put("metadata", context.getMetadata());
        output.putAll(chunkSummary(safeChunks(context)));
        return output;
    }

    private IngestionNodeType resolveNodeType(String nodeType) {
        if (nodeType == null || nodeType.isBlank()) {
            return null;
        }
        try {
            return IngestionNodeType.fromValue(nodeType);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

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

import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点输出提取器
 * 负责从 IngestionContext 中提取特定节点的输出信息
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
            output.put("rawBytesLength", raw.length);
            output.put("rawBytesBase64", Base64.getEncoder().encodeToString(raw));
        }
        return output;
    }

    private Map<String, Object> parserOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mimeType", context.getMimeType());
        output.put("rawText", context.getRawText());
        output.put("document", context.getDocument());
        return output;
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
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("chunkCount", context.getChunks() == null ? 0 : context.getChunks().size());
        output.put("chunks", context.getChunks());
        return output;
    }

    private Map<String, Object> enricherOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("chunkCount", context.getChunks() == null ? 0 : context.getChunks().size());
        output.put("chunks", context.getChunks());
        return output;
    }

    private Map<String, Object> indexerOutput(IngestionContext context, NodeConfig config) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("settings", config.getSettings());
        output.put("chunkCount", context.getChunks() == null ? 0 : context.getChunks().size());
        output.put("chunks", context.getChunks());
        return output;
    }

    private Map<String, Object> genericOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mimeType", context.getMimeType());
        output.put("rawText", context.getRawText());
        output.put("enhancedText", context.getEnhancedText());
        output.put("keywords", context.getKeywords());
        output.put("questions", context.getQuestions());
        output.put("metadata", context.getMetadata());
        output.put("chunks", context.getChunks());
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

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
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentChunk;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.ChunkEnrichType;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.EnricherSettings;
import com.nageoffer.ai.ragent.ingestion.prompt.EnricherPromptManager;
import com.nageoffer.ai.ragent.ingestion.util.JsonResponseParser;
import com.nageoffer.ai.ragent.ingestion.util.PromptTemplateRenderer;
import com.nageoffer.ai.ragent.infra.chat.ChatClient;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文本增强节点
 * 该节点通过调用大模型对文档分片进行信息提取或补充，如提取关键词、生成摘要、补充元数据等
 */
@Component
public class EnricherNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final ModelSelector modelSelector;
    private final Map<String, ChatClient> chatClientsByProvider;

    public EnricherNode(ObjectMapper objectMapper,
                        ModelSelector modelSelector,
                        List<ChatClient> chatClients) {
        this.objectMapper = objectMapper;
        this.modelSelector = modelSelector;
        this.chatClientsByProvider = chatClients.stream()
                .collect(Collectors.toMap(ChatClient::provider, Function.identity()));
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.ENRICHER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        List<DocumentChunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.ok("No chunks to enrich");
        }
        EnricherSettings settings = parseSettings(config.getSettings());
        if (settings.getTasks() == null || settings.getTasks().isEmpty()) {
            return NodeResult.ok("No enricher tasks configured");
        }
        boolean attachMetadata = settings.getAttachDocumentMetadata() == null || settings.getAttachDocumentMetadata();
        for (DocumentChunk chunk : chunks) {
            if (chunk == null || !StringUtils.hasText(chunk.getContent())) {
                continue;
            }
            if (chunk.getMetadata() == null) {
                chunk.setMetadata(new HashMap<>());
            }
            if (attachMetadata && context.getMetadata() != null) {
                chunk.getMetadata().putAll(context.getMetadata());
            }
            for (EnricherSettings.ChunkEnrichTask task : settings.getTasks()) {
                if (task == null || task.getType() == null) {
                    continue;
                }
                ChunkEnrichType type = task.getType();
                String systemPrompt = StringUtils.hasText(task.getSystemPrompt())
                        ? task.getSystemPrompt()
                        : EnricherPromptManager.systemPrompt(type);
                String userPrompt = buildUserPrompt(task.getUserPromptTemplate(), chunk, context);
                ChatRequest request = ChatRequest.builder()
                        .messages(List.of(
                                ChatMessage.system(systemPrompt == null ? "" : systemPrompt),
                                ChatMessage.user(userPrompt)
                        ))
                        .build();
                String response = chat(request, settings.getModelId());
                applyResult(chunk, type, response);
            }
        }
        return NodeResult.ok("Enricher completed");
    }

    private EnricherSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return EnricherSettings.builder().tasks(List.of()).build();
        }
        return objectMapper.convertValue(node, EnricherSettings.class);
    }

    private String buildUserPrompt(String template, DocumentChunk chunk, IngestionContext context) {
        String input = chunk.getContent();
        if (!StringUtils.hasText(template)) {
            return input;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("text", input);
        vars.put("content", input);
        vars.put("chunkIndex", chunk.getIndex());
        vars.put("taskId", context.getTaskId());
        vars.put("pipelineId", context.getPipelineId());
        return PromptTemplateRenderer.render(template, vars);
    }

    private void applyResult(DocumentChunk chunk, ChunkEnrichType type, String response) {
        switch (type) {
            case KEYWORDS -> chunk.getMetadata().put("keywords", JsonResponseParser.parseStringList(response));
            case SUMMARY ->
                    chunk.getMetadata().put("summary", StringUtils.hasText(response) ? response.trim() : response);
            case METADATA -> chunk.getMetadata().putAll(JsonResponseParser.parseObject(response));
            default -> {
            }
        }
    }

    private String chat(ChatRequest request, String modelId) {
        ModelTarget target = resolveChatTarget(modelId, request == null ? null : request.getThinking());
        ChatClient client = chatClientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            throw new ClientException("未找到Chat模型: " + target.candidate().getProvider());
        }
        return client.chat(request, target);
    }

    private ModelTarget resolveChatTarget(String modelId, Boolean thinking) {
        List<ModelTarget> targets = modelSelector.selectChatCandidates(thinking);
        return pickTarget(targets, modelId);
    }

    private ModelTarget pickTarget(List<ModelTarget> targets, String modelId) {
        if (targets == null || targets.isEmpty()) {
            throw new ClientException("未找到可用Chat模型");
        }
        if (!StringUtils.hasText(modelId)) {
            return targets.get(0);
        }
        return targets.stream()
                .filter(target -> modelId.equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new ClientException("未匹配到Chat模型: " + modelId));
    }
}

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
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.EnhanceType;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.EnhancerSettings;
import com.nageoffer.ai.ragent.ingestion.prompt.EnhancerPromptManager;
import com.nageoffer.ai.ragent.ingestion.util.JsonResponseParser;
import com.nageoffer.ai.ragent.ingestion.util.PromptTemplateRenderer;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本增强节点
 * 该节点通过调用大模型对输入的文本进行增强处理，包括不限于上下文增强、关键词提取、问题生成及元数据提取等任务
 */
@Component
public class EnhancerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final LLMService llmService;

    public EnhancerNode(ObjectMapper objectMapper, LLMService llmService) {
        this.objectMapper = objectMapper;
        this.llmService = llmService;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.ENHANCER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        EnhancerSettings settings = parseSettings(config.getSettings());
        if (settings.getTasks() == null || settings.getTasks().isEmpty()) {
            return NodeResult.ok("未配置增强任务");
        }
        if (context.getMetadata() == null) {
            context.setMetadata(new HashMap<>());
        }

        for (EnhancerSettings.EnhanceTask task : settings.getTasks()) {
            if (task == null || task.getType() == null) {
                continue;
            }
            EnhanceType type = task.getType();
            String input = resolveInputText(context, type);
            if (!StringUtils.hasText(input)) {
                continue;
            }
            String systemPrompt = StringUtils.hasText(task.getSystemPrompt())
                    ? task.getSystemPrompt()
                    : EnhancerPromptManager.systemPrompt(type);
            String userPrompt = buildUserPrompt(task.getUserPromptTemplate(), input, context);

            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(systemPrompt == null ? "" : systemPrompt),
                            ChatMessage.user(userPrompt)
                    ))
                    .build();
            String response = chat(request, settings.getModelId());
            applyTaskResult(context, type, response);
        }

        return NodeResult.ok("增强完成");
    }

    private EnhancerSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return EnhancerSettings.builder().tasks(List.of()).build();
        }
        return objectMapper.convertValue(node, EnhancerSettings.class);
    }

    private String resolveInputText(IngestionContext context, EnhanceType type) {
        if (type == EnhanceType.CONTEXT_ENHANCE) {
            return context.getRawText();
        }
        if (StringUtils.hasText(context.getEnhancedText())) {
            return context.getEnhancedText();
        }
        return context.getRawText();
    }

    private String buildUserPrompt(String template, String input, IngestionContext context) {
        if (!StringUtils.hasText(template)) {
            return input;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("text", input);
        vars.put("content", input);
        vars.put("mimeType", context.getMimeType());
        vars.put("taskId", context.getTaskId());
        vars.put("pipelineId", context.getPipelineId());
        return PromptTemplateRenderer.render(template, vars);
    }

    private String chat(ChatRequest request, String modelId) {
        return llmService.chat(request, Tier.FAST, modelId);
    }

    private void applyTaskResult(IngestionContext context, EnhanceType type, String response) {
        switch (type) {
            case CONTEXT_ENHANCE -> context.setEnhancedText(StringUtils.hasText(response) ? response.trim() : response);
            case KEYWORDS -> context.setKeywords(JsonResponseParser.parseStringList(response));
            case QUESTIONS -> context.setQuestions(JsonResponseParser.parseStringList(response));
            case METADATA -> context.getMetadata().putAll(JsonResponseParser.parseObject(response));
            default -> {
            }
        }
    }
}

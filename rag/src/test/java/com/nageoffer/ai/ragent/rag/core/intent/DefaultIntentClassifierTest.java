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

package com.nageoffer.ai.ragent.rag.core.intent;

import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultIntentClassifierTest {

    @Mock
    private LLMService llmService;

    @Mock
    private IntentNodeMapper intentNodeMapper;

    @Mock
    private PromptTemplateLoader promptTemplateLoader;

    @Mock
    private IntentTreeCacheManager intentTreeCacheManager;

    private DefaultIntentClassifier classifier;
    private OrchestrationProperties orchestrationProperties;

    @BeforeEach
    void setUp() {
        orchestrationProperties = new OrchestrationProperties();
        classifier = new DefaultIntentClassifier(
                llmService,
                intentNodeMapper,
                promptTemplateLoader,
                intentTreeCacheManager,
                orchestrationProperties
        );
    }

    @Test
    void skipsLlmWhenIntentTreeIsEmpty() {
        when(intentTreeCacheManager.getIntentTreeFromCache()).thenReturn(List.of());
        when(intentNodeMapper.selectList(any())).thenReturn(List.of());

        List<NodeScore> scores = classifier.classifyTargets("How do I apply for leave?");

        assertTrue(scores.isEmpty());
        verifyNoInteractions(llmService, promptTemplateLoader);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unwrapsJsonExamplesBeforeRenderingPrompt() {
        IntentNodeDO node = IntentNodeDO.builder()
                .intentCode("sys-feedback")
                .name("评价反馈")
                .level(1)
                .kind(1)
                .description("用户对上一轮回答做出评价")
                .examples("[\"回答得不错\",\"你答错了\"]")
                .build();
        when(intentTreeCacheManager.getIntentTreeFromCache()).thenReturn(null);
        when(intentNodeMapper.selectList(any())).thenReturn(List.of(node));
        when(promptTemplateLoader.render(anyString(), anyMap())).thenReturn("system-prompt");
        when(llmService.chat(any())).thenReturn("[]");

        classifier.classifyTargets("回答得不错");

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(promptTemplateLoader).render(anyString(), captor.capture());
        assertTrue(captor.getValue().get("intent_list").contains("examples=回答得不错 / 你答错了"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void agentModeOnlyPromptsAndAcceptsKbCandidates() {
        orchestrationProperties.setType("agent");
        IntentNode kb = IntentNode.builder()
                .id("kb-leave")
                .name("请假制度")
                .kind(IntentKind.KB)
                .build();
        IntentNode mcp = IntentNode.builder()
                .id("mcp-leave")
                .name("提交请假")
                .kind(IntentKind.MCP)
                .mcpToolId("leave_submit")
                .build();
        IntentNode system = IntentNode.builder()
                .id("system-welcome")
                .name("欢迎语")
                .kind(IntentKind.SYSTEM)
                .build();
        when(intentTreeCacheManager.getIntentTreeFromCache()).thenReturn(List.of(kb, mcp, system));
        when(promptTemplateLoader.render(anyString(), anyMap())).thenReturn("system-prompt");
        when(llmService.chat(any())).thenReturn("""
                [
                  {"id":"kb-leave","score":0.9},
                  {"id":"mcp-leave","score":0.8},
                  {"id":"system-welcome","score":0.7}
                ]
                """);

        List<NodeScore> scores = classifier.classifyTargets("帮我请假");

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(promptTemplateLoader).render(anyString(), captor.capture());
        String intentList = captor.getValue().get("intent_list");
        assertTrue(intentList.contains("id=kb-leave"));
        assertFalse(intentList.contains("id=mcp-leave"));
        assertFalse(intentList.contains("id=system-welcome"));
        assertEquals(List.of("kb-leave"), scores.stream().map(score -> score.getNode().getId()).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void workflowModeExcludesMcpCandidatesButKeepsSystemAndKnowledge() {
        orchestrationProperties.setType("workflow");
        IntentNode kb = IntentNode.builder()
                .id("kb-leave")
                .name("请假制度")
                .kind(IntentKind.KB)
                .build();
        IntentNode mcp = IntentNode.builder()
                .id("mcp-leave")
                .name("提交请假")
                .kind(IntentKind.MCP)
                .mcpToolId("leave_submit")
                .build();
        IntentNode system = IntentNode.builder()
                .id("system-welcome")
                .name("欢迎语")
                .kind(IntentKind.SYSTEM)
                .build();
        when(intentTreeCacheManager.getIntentTreeFromCache()).thenReturn(List.of(kb, mcp, system));
        when(promptTemplateLoader.render(anyString(), anyMap())).thenReturn("system-prompt");
        when(llmService.chat(any())).thenReturn("""
                [
                  {"id":"kb-leave","score":0.9},
                  {"id":"mcp-leave","score":0.8},
                  {"id":"system-welcome","score":0.7}
                ]
                """);

        List<NodeScore> scores = classifier.classifyTargets("帮我请假");

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(promptTemplateLoader).render(anyString(), captor.capture());
        String intentList = captor.getValue().get("intent_list");
        assertTrue(intentList.contains("id=kb-leave"));
        assertTrue(intentList.contains("id=system-welcome"));
        assertFalse(intentList.contains("id=mcp-leave"));
        assertEquals(List.of("kb-leave", "system-welcome"),
                scores.stream().map(score -> score.getNode().getId()).toList());
    }
}

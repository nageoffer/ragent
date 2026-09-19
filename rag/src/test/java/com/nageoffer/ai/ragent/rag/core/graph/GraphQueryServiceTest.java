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

package com.nageoffer.ai.ragent.rag.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.controller.vo.GraphViewVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphQueryServiceTest {

    private final LightRagClient lightRagClient = mock(LightRagClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private GraphQueryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<LightRagClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(lightRagClient);
        service = new GraphQueryService(provider);
    }

    @Test
    @DisplayName("知识库过滤按 file_path 分量精确匹配并保留多来源节点")
    void collectionFilterMatchesExactSourceComponents() throws Exception {
        when(lightRagClient.fetchGraph("*", 2, 1000)).thenReturn(objectMapper.readTree("""
                {
                  "nodes": [
                    {"id":"exact","properties":{"entity_id":"本库节点","file_path":"kb_1954071234567890100"}},
                    {"id":"prefix","properties":{"entity_id":"前缀重叠别库节点","file_path":"kb_hr_1954071234567890200"}},
                    {"id":"merged","properties":{"entity_id":"多来源节点","file_path":"sales_1954071234567890300<SEP>kb_1954071234567890400"}},
                    {"id":"unknown","properties":{"entity_id":"未知来源节点","file_path":"readme.txt"}}
                  ],
                  "edges": [
                    {"id":"kept","source":"exact","target":"merged","properties":{}},
                    {"id":"dropped","source":"exact","target":"prefix","properties":{}}
                  ]
                }
                """));

        GraphViewVO graph = service.getGraph(null, "kb", null, 0, 0);

        assertThat(graph.getNodes())
                .extracting(GraphViewVO.Node::getId)
                .containsExactly("exact", "merged");
        assertThat(graph.getEdges())
                .extracting(GraphViewVO.Edge::getId)
                .containsExactly("kept");
    }

    @Test
    @DisplayName("文档过滤按解析后的 docId 精确匹配并优先于知识库")
    void documentFilterMatchesExactSourceComponents() throws Exception {
        when(lightRagClient.fetchGraph("*", 2, 1000)).thenReturn(objectMapper.readTree("""
                {
                  "nodes": [
                    {"id":"exact","properties":{"entity_id":"目标文档节点","file_path":"kb_1954071234567890100"}},
                    {"id":"substring","properties":{"entity_id":"子串重叠文档节点","file_path":"kb_91954071234567890100"}},
                    {"id":"merged","properties":{"entity_id":"多来源节点","file_path":"sales_1954071234567890300<SEP>kb_1954071234567890100"}}
                  ],
                  "edges": []
                }
                """));

        GraphViewVO graph = service.getGraph(null, "other", "1954071234567890100", 0, 0);

        assertThat(graph.getNodes())
                .extracting(GraphViewVO.Node::getId)
                .containsExactly("exact", "merged");
    }
}

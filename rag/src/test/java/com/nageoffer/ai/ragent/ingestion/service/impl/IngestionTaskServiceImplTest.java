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

package com.nageoffer.ai.ragent.ingestion.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskNodeVO;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskVO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskDO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskNodeDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskNodeMapper;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionTaskServiceImplTest {

    @Mock
    private IngestionEngine engine;

    @Mock
    private IngestionPipelineService pipelineService;

    @Mock
    private IngestionTaskMapper taskMapper;

    @Mock
    private IngestionTaskNodeMapper taskNodeMapper;

    @Mock
    private BizChangeLogContext bizChangeLogContext;

    private IngestionTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IngestionTaskServiceImpl(
                engine,
                pipelineService,
                taskMapper,
                taskNodeMapper,
                new ObjectMapper(),
                bizChangeLogContext
        );
    }

    @Test
    void getParsesMetadataJsonInsteadOfTreatingItAsABean() {
        IngestionTaskDO task = IngestionTaskDO.builder()
                .id("task-1")
                .pipelineId("pipeline-1")
                .metadataJson("{\"keywords\":[\"rag\",\"agent\"],\"stats\":{\"chunks\":3}}")
                .build();
        when(taskMapper.selectById("task-1")).thenReturn(task);

        IngestionTaskVO result = service.get("task-1");

        assertEquals(List.of("rag", "agent"), result.getMetadata().get("keywords"));
        Map<?, ?> stats = (Map<?, ?>) result.getMetadata().get("stats");
        assertEquals(3, ((Number) stats.get("chunks")).intValue());
    }

    @Test
    void listNodesParsesNestedOutputJson() {
        IngestionTaskNodeDO node = IngestionTaskNodeDO.builder()
                .id("node-record-1")
                .taskId("task-1")
                .pipelineId("pipeline-1")
                .nodeId("fetcher-1")
                .outputJson("{\"rawBytesLength\":128,\"source\":{\"type\":\"file\"}}")
                .build();
        when(taskNodeMapper.selectList(any())).thenReturn(List.of(node));

        List<IngestionTaskNodeVO> result = service.listNodes("task-1");

        assertEquals(128, ((Number) result.get(0).getOutput().get("rawBytesLength")).intValue());
        Map<?, ?> source = (Map<?, ?>) result.get(0).getOutput().get("source");
        assertEquals("file", source.get("type"));
    }

    @Test
    void invalidOrBlankJsonFallsBackToEmptyMaps() {
        IngestionTaskDO task = IngestionTaskDO.builder()
                .id("task-1")
                .pipelineId("pipeline-1")
                .metadataJson("{invalid")
                .build();
        IngestionTaskNodeDO node = IngestionTaskNodeDO.builder()
                .id("node-record-1")
                .taskId("task-1")
                .pipelineId("pipeline-1")
                .nodeId("fetcher-1")
                .outputJson(" ")
                .build();
        when(taskMapper.selectById("task-1")).thenReturn(task);
        when(taskNodeMapper.selectList(any())).thenReturn(List.of(node));

        assertTrue(service.get("task-1").getMetadata().isEmpty());
        assertTrue(service.listNodes("task-1").get(0).getOutput().isEmpty());

        task.setMetadataJson("null");
        assertTrue(service.get("task-1").getMetadata().isEmpty());
    }

    @Test
    void uploadPropagatesEngineClientExceptionWithoutWrapping() {
        ClientException engineFailure = new ClientException("流水线存在多个起始节点: a-root, z-root");
        when(pipelineService.getDefinition("pipeline-1")).thenReturn(mock(PipelineDefinition.class));
        when(engine.execute(any(), any())).thenThrow(engineFailure);
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        ClientException thrown = assertThrows(ClientException.class, () -> service.upload("pipeline-1", file));

        assertSame(engineFailure, thrown);
    }
}

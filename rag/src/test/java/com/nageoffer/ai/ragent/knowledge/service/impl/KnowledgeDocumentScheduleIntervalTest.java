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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.core.ingest.IngestionKernel;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter;
import com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.enums.SourceType;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentScheduleService;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecCodec;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编辑文档时 cron 最小间隔校验的阈值来源
 * <p>
 * 上传入口（{@code validateSourceAndSchedule}）与调度侧（{@code KnowledgeDocumentScheduleServiceImpl}）
 * 都读 {@code rag.knowledge.schedule.min-interval-seconds}，编辑入口若改用字面量，
 * 该配置就对编辑失效：收紧时编辑可绕过下限，放宽时编辑反而误拒合法表达式。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentScheduleIntervalTest {

    private static final String DOC_ID = "doc-1";

    /**
     * 每 20 秒触发一次：分钟边界不打断（0→20→40→下一分 0 秒），间隔恒为 20 秒
     * <p>
     * 不能用 {@code 0/45 * * * * *} 这类每秒同余的写法——它的相邻间隔在 45 与 15 之间
     * 随当前时刻跳变，断言会随运行时刻偶发失败
     */
    private static final String CRON_EVERY_20_SECONDS = "0,20,40 * * * * *";

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private ParserRegistry parserRegistry;
    @Mock
    private IngestionKernel ingestionKernel;
    @Mock
    private ChunkIndexWriter chunkIndexWriter;
    @Mock
    private IngestionSpecCodec ingestionSpecCodec;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private KnowledgeChunkService knowledgeChunkService;
    @Mock
    private KnowledgeDocumentScheduleService scheduleService;
    @Mock
    private IngestionPipelineService ingestionPipelineService;
    @Mock
    private IngestionPipelineMapper ingestionPipelineMapper;
    @Mock
    private IngestionEngine ingestionEngine;
    @Mock
    private KnowledgeDocumentChunkLogMapper chunkLogMapper;
    @Mock
    private KnowledgeChunkMapper chunkMapper;
    @Mock
    private TransactionOperations transactionOperations;
    @Mock
    private MessageQueueProducer messageQueueProducer;
    @Mock
    private RemoteFileFetcher remoteFileFetcher;
    @Mock
    private VectorTargetResolver vectorTargetResolver;
    @Mock
    private BizChangeLogContext bizChangeLogContext;

    private KnowledgeScheduleProperties scheduleProperties;
    private KnowledgeDocumentServiceImpl documentService;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper 解析列名依赖 TableInfo 缓存，纯单测环境需手动初始化
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeDocumentDO.class);
        scheduleProperties = new KnowledgeScheduleProperties();
        documentService = new KnowledgeDocumentServiceImpl(
                knowledgeBaseMapper,
                documentMapper,
                parserRegistry,
                ingestionKernel,
                chunkIndexWriter,
                ingestionSpecCodec,
                fileStorageService,
                vectorStoreService,
                knowledgeChunkService,
                new ObjectMapper(),
                scheduleService,
                ingestionPipelineService,
                ingestionPipelineMapper,
                ingestionEngine,
                chunkLogMapper,
                chunkMapper,
                transactionOperations,
                messageQueueProducer,
                scheduleProperties,
                remoteFileFetcher,
                vectorTargetResolver,
                bizChangeLogContext
        );
    }

    @Test
    void shouldAcceptCronOnlyBelowTheOldHardcodedThreshold() {
        // 下限放宽到 15 秒后，20 秒的表达式必须可保存；按写死的 60 判断会误拒
        scheduleProperties.setMinIntervalSeconds(15L);
        when(documentMapper.selectById(DOC_ID)).thenReturn(urlDocument());
        when(documentMapper.update(any())).thenReturn(1);

        assertDoesNotThrow(() -> documentService.update(DOC_ID, updateRequest(CRON_EVERY_20_SECONDS)));

        verify(scheduleService).upsertSchedule(any(KnowledgeDocumentDO.class));
    }

    @Test
    void shouldRejectCronBelowConfiguredIntervalAndReportConfiguredValue() {
        // 下限收紧到 30 秒后，20 秒的表达式必须被拒绝，且报错里的数字要跟着配置走
        scheduleProperties.setMinIntervalSeconds(30L);
        when(documentMapper.selectById(DOC_ID)).thenReturn(urlDocument());

        ClientException exception = assertThrows(ClientException.class,
                () -> documentService.update(DOC_ID, updateRequest(CRON_EVERY_20_SECONDS)));

        assertEquals("定时周期不能小于 30 秒", exception.getMessage());
        verify(scheduleService, never()).upsertSchedule(any(KnowledgeDocumentDO.class));
    }

    @Test
    void shouldLeaveScheduleUntouchedWhenCronIsNotSubmitted() {
        // 只改文档名的请求不涉及 cron，不应触发校验，也不应重建调度
        scheduleProperties.setMinIntervalSeconds(30L);
        when(documentMapper.selectById(DOC_ID)).thenReturn(urlDocument());
        when(documentMapper.update(any())).thenReturn(1);

        assertDoesNotThrow(() -> documentService.update(DOC_ID, updateRequest(null)));

        verify(scheduleService, never()).upsertSchedule(any(KnowledgeDocumentDO.class));
    }

    @Test
    void shouldKeepInvalidCronReportedAsExpressionError() {
        // 语法错误仍走原有分支，不要被间隔校验的错误信息吞掉
        scheduleProperties.setMinIntervalSeconds(15L);
        when(documentMapper.selectById(DOC_ID)).thenReturn(urlDocument());

        ClientException exception = assertThrows(ClientException.class,
                () -> documentService.update(DOC_ID, updateRequest("not-a-cron")));

        assertTrue(exception.getMessage().startsWith("定时表达式不合法"),
                "实际报错：" + exception.getMessage());
    }

    private KnowledgeDocumentDO urlDocument() {
        return KnowledgeDocumentDO.builder()
                .id(DOC_ID)
                .kbId("kb-1")
                .docName("旧名字")
                .sourceType(SourceType.URL.getValue())
                .sourceLocation("https://example.com/doc.pdf")
                .scheduleEnabled(0)
                .scheduleCron("0 0/5 * * * *")
                .enabled(1)
                .status(DocumentStatus.SUCCESS.getCode())
                .deleted(0)
                .build();
    }

    private KnowledgeDocumentUpdateRequest updateRequest(String cron) {
        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setDocName("新名字");
        request.setScheduleCron(cron);
        return request;
    }
}

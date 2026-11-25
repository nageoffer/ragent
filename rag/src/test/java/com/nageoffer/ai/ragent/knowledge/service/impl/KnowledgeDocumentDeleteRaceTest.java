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
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文档删除与分块的互斥回归测试
 *
 * <p>覆盖 issue #42 描述的 TOCTOU 场景：删除流程改为状态 CAS 抢占后，
 * 必须保证「分块进行中不允许删除」和「删除进行中不允许再进入删除」两条约束，
 * 且 CAS 一定发生在任何拆除动作（删分块、删日志、删向量、删文件）之前。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentDeleteRaceTest {

    private static final String DOC_ID = "doc-1";

    private static final String KB_ID = "1";

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
    private ObjectMapper objectMapper;

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
    private KnowledgeScheduleProperties scheduleProperties;

    @Mock
    private RemoteFileFetcher remoteFileFetcher;

    @Mock
    private VectorTargetResolver vectorTargetResolver;

    @Mock
    private BizChangeLogContext bizChangeLogContext;

    private KnowledgeDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper 解析列名依赖 TableInfo 缓存，纯单测环境需手动初始化
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeDocumentDO.class);

        service = new KnowledgeDocumentServiceImpl(
                knowledgeBaseMapper,
                documentMapper,
                parserRegistry,
                ingestionKernel,
                chunkIndexWriter,
                ingestionSpecCodec,
                fileStorageService,
                vectorStoreService,
                knowledgeChunkService,
                objectMapper,
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
                bizChangeLogContext);
    }

    @Test
    void deleteShouldRejectWhenChunkIsStillRunning() {
        // 分块已把状态推到 RUNNING：CAS 抢不到删除权，且不得产生任何拆除副作用
        KnowledgeDocumentDO running = document(DocumentStatus.RUNNING);
        when(documentMapper.selectById(DOC_ID)).thenReturn(running);
        when(documentMapper.update(any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.delete(DOC_ID))
                .isInstanceOf(ClientException.class)
                .hasMessage("文档正在分块中，无法删除");

        verify(scheduleService, never()).deleteByDocId(anyString());
        verify(documentMapper, never()).deleteById(any(KnowledgeDocumentDO.class));
        verifyNoInteractions(chunkIndexWriter, fileStorageService);
    }

    @Test
    void deleteShouldRejectWhenAnotherDeleteAlreadyAcquiredTheDocument() {
        // 并发删除：另一个请求已把状态改成 DELETING，本次 CAS 同样必须失败
        KnowledgeDocumentDO pending = document(DocumentStatus.PENDING);
        KnowledgeDocumentDO deleting = document(DocumentStatus.DELETING);
        when(documentMapper.selectById(DOC_ID)).thenReturn(pending, deleting);
        when(documentMapper.update(any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.delete(DOC_ID))
                .isInstanceOf(ClientException.class)
                .hasMessage("文档正在删除中，请稍后重试");

        verify(scheduleService, never()).deleteByDocId(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteShouldAcquireDeletingStatusBeforeRemovingAnyChunk() {
        // 正常路径：状态 CAS 必须先于任何拆除动作，且更新条件里同时排除 RUNNING 与 DELETING
        KnowledgeDocumentDO pending = document(DocumentStatus.PENDING);
        when(documentMapper.selectById(DOC_ID)).thenReturn(pending);
        when(documentMapper.update(any(Wrapper.class))).thenReturn(1);

        service.delete(DOC_ID);

        ArgumentCaptor<Wrapper<KnowledgeDocumentDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        InOrder order = inOrder(documentMapper, scheduleService);
        order.verify(documentMapper).update(captor.capture());
        order.verify(scheduleService).deleteByDocId(DOC_ID);

        LambdaUpdateWrapper<KnowledgeDocumentDO> wrapper =
                (LambdaUpdateWrapper<KnowledgeDocumentDO>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("status");
        // SET 目标状态为 deleting，WHERE 里带上 running 与 deleting 两个互斥条件
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                DocumentStatus.DELETING.getCode(),
                DocumentStatus.RUNNING.getCode(),
                DocumentStatus.DELETING.getCode());
        assertThat(wrapper.getParamNameValuePairs().values()).doesNotContain(DocumentStatus.SUCCESS.getCode());
    }

    private KnowledgeDocumentDO document(DocumentStatus status) {
        return KnowledgeDocumentDO.builder()
                .id(DOC_ID)
                .kbId(KB_ID)
                .docName("并发测试文档")
                .status(status.getCode())
                .build();
    }
}

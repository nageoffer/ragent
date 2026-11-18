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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationSummaryMapper;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationCreateBO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationServiceImplTitleTest {

    private static final String USER_ID = "u-1";
    private static final String CONVERSATION_ID = "c-1";

    private ConversationMapper conversationMapper;
    private ConversationTitleGenerator titleGenerator;
    private ConversationTitleTaskRegistry titleTaskRegistry;
    private MemoryProperties memoryProperties;

    @BeforeAll
    static void initTableInfo() {
        // 单测无 Spring 容器，需手动初始化 MyBatis-Plus 的 Lambda 列缓存，否则 Wrappers.lambdaUpdate 会报错
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ConversationDO.class);
    }

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ConversationMapper.class);
        titleGenerator = mock(ConversationTitleGenerator.class);
        titleTaskRegistry = mock(ConversationTitleTaskRegistry.class);
        memoryProperties = new MemoryProperties();
        memoryProperties.setTitleMaxLength(10);
    }

    @Test
    void shouldInsertPlaceholderTitleAndBackfillGeneratedTitle() {
        when(titleGenerator.generate("年假怎么计算？入职多久可以休年假？")).thenReturn(" 年假计算和入职时长 ");
        ConversationServiceImpl service = newService(Runnable::run);

        service.createOrUpdate(request("年假怎么计算？入职多久可以休年假？"));

        ArgumentCaptor<ConversationDO> inserted = ArgumentCaptor.forClass(ConversationDO.class);
        verify(conversationMapper).insert(inserted.capture());
        // 占位标题为问题按 titleMaxLength 截断
        assertThat(inserted.getValue().getTitle()).isEqualTo("年假怎么计算？入职多");
        // 生成成功后以条件更新回填
        verify(conversationMapper).update(isNull(), any());
        verify(titleTaskRegistry).register(any(), any(), any());
    }

    @Test
    void shouldSkipBackfillWhenGeneratedTitleEqualsPlaceholder() {
        when(titleGenerator.generate("你好")).thenReturn("你好");
        ConversationServiceImpl service = newService(Runnable::run);

        service.createOrUpdate(request("你好"));

        verify(conversationMapper).insert(any(ConversationDO.class));
        verify(conversationMapper, never()).update(any(), any());
    }

    @Test
    void shouldSkipBackfillWhenGeneratedTitleIsBlank() {
        when(titleGenerator.generate("你好")).thenReturn("  ");
        ConversationServiceImpl service = newService(Runnable::run);

        service.createOrUpdate(request("你好"));

        verify(conversationMapper, never()).update(any(), any());
    }

    @Test
    void shouldKeepPlaceholderWhenGenerationThrows() {
        when(titleGenerator.generate("你好")).thenThrow(new IllegalStateException("LLM 不可用"));
        ConversationServiceImpl service = newService(Runnable::run);

        assertThatCode(() -> service.createOrUpdate(request("你好"))).doesNotThrowAnyException();

        verify(conversationMapper).insert(any(ConversationDO.class));
        verify(conversationMapper, never()).update(any(), any());
    }

    @Test
    void shouldKeepPlaceholderWhenExecutorRejectsTask() {
        Executor rejecting = command -> {
            throw new RejectedExecutionException("队列已满");
        };
        ConversationServiceImpl service = newService(rejecting);

        assertThatCode(() -> service.createOrUpdate(request("你好"))).doesNotThrowAnyException();

        ArgumentCaptor<ConversationDO> inserted = ArgumentCaptor.forClass(ConversationDO.class);
        verify(conversationMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getTitle()).isEqualTo("你好");
        verifyNoInteractions(titleGenerator);
        verify(titleTaskRegistry, never()).register(any(), any(), any());
    }

    @Test
    void shouldUseDefaultTitleWhenQuestionIsBlank() {
        ConversationServiceImpl service = newService(command -> {
        });

        service.createOrUpdate(request("   "));

        ArgumentCaptor<ConversationDO> inserted = ArgumentCaptor.forClass(ConversationDO.class);
        verify(conversationMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getTitle()).isEqualTo("新对话");
    }

    @Test
    void shouldNotGenerateTitleForExistingConversation() {
        ConversationDO existing = ConversationDO.builder()
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .title("已有标题")
                .build();
        when(conversationMapper.selectOne(any())).thenReturn(existing);
        ConversationServiceImpl service = newService(Runnable::run);

        service.createOrUpdate(request("第二个问题"));

        verify(conversationMapper).updateById(existing);
        verify(conversationMapper, never()).insert(any(ConversationDO.class));
        verifyNoInteractions(titleGenerator, titleTaskRegistry);
    }

    private ConversationServiceImpl newService(Executor executor) {
        return new ConversationServiceImpl(
                conversationMapper,
                mock(ConversationMessageMapper.class),
                mock(ConversationSummaryMapper.class),
                memoryProperties,
                titleGenerator,
                titleTaskRegistry,
                executor
        );
    }

    private ConversationCreateBO request(String question) {
        return ConversationCreateBO.builder()
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .question(question)
                .lastTime(new Date())
                .build();
    }
}

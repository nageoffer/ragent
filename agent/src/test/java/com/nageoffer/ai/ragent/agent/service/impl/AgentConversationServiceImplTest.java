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

package com.nageoffer.ai.ragent.agent.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentConversationDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentConversationMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmSettlement;
import com.nageoffer.ai.ragent.agent.enums.AgentMessageStatus;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.rag.service.impl.ConversationTitleGenerator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentConversationServiceImplTest {

    private static final String USER_ID = "u-1001";
    private static final String CONVERSATION_ID = "c-2002";

    static {
        // 脱离 SqlSession 时 lambda 列名缓存是空的，条件构造器取不出 SQL 片段
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentMessageDO.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentConversationDO.class);
    }

    private AgentConversationMapper conversationMapper;
    private AgentMessageMapper messageMapper;
    private PgAgentStateStore agentStateStore;
    private AgentRunGate runGate;
    private ReActAgentProvider agentProvider;
    private AgentConversationServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        conversationMapper = mock(AgentConversationMapper.class);
        messageMapper = mock(AgentMessageMapper.class);
        agentStateStore = mock(PgAgentStateStore.class);
        runGate = mock(AgentRunGate.class);
        ConversationTitleGenerator titleGenerator = mock(ConversationTitleGenerator.class);
        agentProvider = mock(ReActAgentProvider.class);
        ObjectProvider<ReActAgentProvider> agentProviderRef = mock(ObjectProvider.class);
        when(agentProviderRef.getIfAvailable()).thenReturn(agentProvider);
        when(conversationMapper.delete(any())).thenReturn(1);
        when(messageMapper.delete(any())).thenReturn(1);
        service = new AgentConversationServiceImpl(
                conversationMapper, messageMapper, agentStateStore, runGate, titleGenerator, agentProviderRef);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldEvictAgentStateCacheWhenConversationDeleted() {
        service.delete(CONVERSATION_ID, USER_ID);

        // 表清了内存不清，单例 Agent 会带着已删记忆继续对话并把状态写回 PG
        verify(agentProvider).evictStateCache(USER_ID, CONVERSATION_ID);
    }

    @Test
    void shouldEvictEachConversationWhenBatchDeleted() {
        service.deleteBatch(List.of(CONVERSATION_ID, "c-3003", CONVERSATION_ID), USER_ID);

        // 重复 ID 去重后每个会话各驱逐一次
        verify(agentProvider, times(1)).evictStateCache(USER_ID, CONVERSATION_ID);
        verify(agentProvider, times(1)).evictStateCache(USER_ID, "c-3003");
    }

    @Test
    void shouldEvictOnlyAfterTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();

        service.delete(CONVERSATION_ID, USER_ID);

        // 事务还没提就驱逐内存，一旦回滚就成了表还在记忆没了
        verify(agentProvider, never()).evictStateCache(USER_ID, CONVERSATION_ID);
        commitCurrentTransaction();
        verify(agentProvider).evictStateCache(USER_ID, CONVERSATION_ID);
    }

    @Test
    void shouldEvictBatchAfterSingleCommit() {
        TransactionSynchronizationManager.initSynchronization();

        service.deleteBatch(List.of(CONVERSATION_ID, "c-3003"), USER_ID);

        verify(agentProvider, never()).evictStateCache(any(), any());
        commitCurrentTransaction();
        verify(agentProvider, times(1)).evictStateCache(USER_ID, CONVERSATION_ID);
        verify(agentProvider, times(1)).evictStateCache(USER_ID, "c-3003");
    }

    @Test
    void shouldRejectDeleteWhileConversationIsRunning() {
        when(runGate.runningTaskId(USER_ID, CONVERSATION_ID)).thenReturn("t-9001");

        assertThatThrownBy(() -> service.delete(CONVERSATION_ID, USER_ID))
                .hasMessageContaining("正在生成中");

        // 放行就会让在途流把状态和消息写回已删会话，留下够不着的残行
        verify(conversationMapper, never()).delete(any());
        verify(messageMapper, never()).delete(any());
        verify(agentStateStore, never()).delete(any(), any());
    }

    @Test
    void shouldAllowDeleteWhenAnotherConversationIsRunning() {
        // 该用户确实有流在跑，但跑的是别的会话，不该连累这一个
        when(runGate.runningTaskId(USER_ID, CONVERSATION_ID)).thenReturn(null);

        service.delete(CONVERSATION_ID, USER_ID);

        verify(conversationMapper).delete(any());
        verify(agentProvider).evictStateCache(USER_ID, CONVERSATION_ID);
    }

    @Test
    void shouldRejectWholeBatchWhenOneConversationIsRunning() {
        TransactionSynchronizationManager.initSynchronization();
        when(runGate.runningTaskId(USER_ID, "c-3003")).thenReturn("t-9001");

        assertThatThrownBy(() -> service.deleteBatch(List.of(CONVERSATION_ID, "c-3003"), USER_ID))
                .hasMessageContaining("正在生成中");

        // 整批一个事务，挡下一个就全回滚，驱逐缓存不该走到
        verify(agentProvider, never()).evictStateCache(any(), any());
    }

    @Test
    void shouldReadConfirmationContextWithoutSettlingCard() {
        when(conversationMapper.selectOne(any())).thenReturn(existingConversation("原会话"));
        AgentMessageDO message = pendingConfirmation();
        when(messageMapper.selectOne(any())).thenReturn(message);

        AgentConfirmSettlement context = service.getPendingConfirm(CONVERSATION_ID, USER_ID, "m-4004");

        assertThat(context.title()).isEqualTo("原会话");
        assertThat(context.replyToMessageId()).isEqualTo("m-3003");
        assertThat(message.getMessageStatus()).isEqualTo(AgentMessageStatus.AWAITING_CONFIRM.name());
        assertThat(message.getBlocks().get(0).getStatus()).isEqualTo("pending");
        verify(messageMapper, never()).updateById(any(AgentMessageDO.class));
    }

    @Test
    void shouldRevalidateCardWhenSettlingAfterRead() {
        when(conversationMapper.selectOne(any())).thenReturn(existingConversation("原会话"));
        AgentMessageDO message = pendingConfirmation();
        when(messageMapper.selectOne(any())).thenReturn(message);
        service.getPendingConfirm(CONVERSATION_ID, USER_ID, "m-4004");
        message.setMessageStatus(AgentMessageStatus.NORMAL.name());

        assertThatThrownBy(() -> service.settlePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004", true))
                .hasMessageContaining("已处理");

        verify(messageMapper, never()).updateById(any(AgentMessageDO.class));
    }

    private static AgentMessageDO pendingConfirmation() {
        AgentMessageDO message = assistantRow("m-4004", "m-3003", "确认操作", AgentMessageStatus.AWAITING_CONFIRM);
        message.setBlocks(List.of(AgentBlock.builder().kind("confirm").status("pending").build()));
        return message;
    }

    @Test
    void shouldPurgeResidueWhenConversationRecreated() {
        // 会话行查不到就要建：同号的状态与消息只可能是删除后的残骸
        when(conversationMapper.selectOne(any())).thenReturn(null);

        service.touchConversation(CONVERSATION_ID, USER_ID, "本轮提问");

        verify(agentStateStore).delete(USER_ID, CONVERSATION_ID);
        verify(messageMapper).delete(any());
        verify(agentProvider).evictStateCache(USER_ID, CONVERSATION_ID);
    }

    @Test
    void shouldPurgeBeforeCreatingConversationRow() {
        when(conversationMapper.selectOne(any())).thenReturn(null);
        InOrder order = inOrder(agentStateStore, conversationMapper);

        service.touchConversation(CONVERSATION_ID, USER_ID, "本轮提问");

        // 清失败就不该建行：留下「会话行是新的、记忆是旧的」比不建更糟，重试还会再清一遍
        order.verify(agentStateStore).delete(USER_ID, CONVERSATION_ID);
        order.verify(conversationMapper).insert(any(AgentConversationDO.class));
    }

    @Test
    void shouldNotPurgeWhenConversationStillAlive() {
        when(conversationMapper.selectOne(any())).thenReturn(existingConversation("老会话"));

        service.touchConversation(CONVERSATION_ID, USER_ID, "本轮提问");

        // 会话还活着，清就是把用户的记忆和消息删了
        verify(agentStateStore, never()).delete(any(), any());
        verify(messageMapper, never()).delete(any());
        verify(agentProvider, never()).evictStateCache(any(), any());
    }

    @Test
    void shouldDeclareTransactionOnDeletePaths() throws NoSuchMethodException {
        // 三步删中途失败会留半删状态，注解掉了就没人拦
        assertThat(AgentConversationServiceImpl.class
                .getDeclaredMethod("delete", String.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(AgentConversationServiceImpl.class
                .getDeclaredMethod("deleteBatch", List.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void shouldReturnExistingTitleWhenInsertLosesRace() {
        // 并发首问：两侧都查空各自插入，落败方撞唯一索引
        when(conversationMapper.selectOne(any())).thenReturn(null, existingConversation("赢家标题"));
        when(conversationMapper.insert(any(AgentConversationDO.class)))
                .thenThrow(new DuplicateKeyException("uk_agent_conversation_user"));

        String title = service.touchConversation(CONVERSATION_ID, USER_ID, "本轮提问");

        assertThat(title).isEqualTo("赢家标题");
        verify(conversationMapper, times(2)).selectOne(any());
    }

    @Test
    void shouldRethrowWhenDuplicateKeyIsNotRecoverable() {
        // 撞键却重查不到，说明冲突另有来源，不能吞掉
        when(conversationMapper.selectOne(any())).thenReturn(null, (AgentConversationDO) null);
        when(conversationMapper.insert(any(AgentConversationDO.class)))
                .thenThrow(new DuplicateKeyException("uk_agent_conversation_user"));

        assertThatThrownBy(() -> service.touchConversation(CONVERSATION_ID, USER_ID, "本轮提问"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private static AgentMessageDO assistantRow(String id, String replyTo, String content, AgentMessageStatus status) {
        return AgentMessageDO.builder()
                .id(id)
                .role("assistant")
                .content(content)
                .replyToMessageId(replyTo)
                .messageStatus(status.name())
                .build();
    }

    /**
     * 模拟事务提交：驱动已注册的同步回调走 afterCommit
     */
    private void commitCurrentTransaction() {
        List.copyOf(TransactionSynchronizationManager.getSynchronizations())
                .forEach(TransactionSynchronization::afterCommit);
    }

    private AgentConversationDO existingConversation(String title) {
        return AgentConversationDO.builder()
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .title(title)
                .lastTime(new Date())
                .build();
    }
}

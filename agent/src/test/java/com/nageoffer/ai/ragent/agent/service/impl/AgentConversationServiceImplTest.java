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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentConversationDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentConversationMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmCall;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmSettlement;
import com.nageoffer.ai.ragent.agent.enums.AgentMessageStatus;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
        agentProvider = mock(ReActAgentProvider.class);
        ObjectProvider<ReActAgentProvider> agentProviderRef = mock(ObjectProvider.class);
        when(agentProviderRef.getIfAvailable()).thenReturn(agentProvider);
        when(conversationMapper.delete(any())).thenReturn(1);
        when(messageMapper.delete(any())).thenReturn(1);
        service = new AgentConversationServiceImpl(
                conversationMapper, messageMapper, agentStateStore, runGate, agentProviderRef);
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

        // 删除数据库状态后同步清理本节点可能残留的会话缓存
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

        // 仅在数据库确认删除后失效缓存，回滚时保留原有内存状态
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
    void shouldDenyLinkedAwaitingToolInSameMessageUpdate() {
        AgentBlock tool = toolBlock("call-1", "awaiting");
        AgentMessageDO message = confirmationWithTools(List.of("call-1"), tool);

        AgentConfirmSettlement settlement = service.settlePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004", false);

        assertThat(settlement.replyToMessageId()).isEqualTo("m-3003");
        assertThat(message.getMessageStatus()).isEqualTo("NORMAL");
        ArgumentCaptor<AgentMessageDO> update = ArgumentCaptor.forClass(AgentMessageDO.class);
        verify(messageMapper).updateById(update.capture());
        assertThat(update.getValue().getBlocks()).extracting(AgentBlock::getStatus)
                .containsExactly("denied", "denied");
        assertThat(tool.getResult()).isNull();
        assertThat(tool.getStartedAt()).isNull();
        assertThat(tool.getEndedAt()).isNull();
        assertThat(tool.getDurationMs()).isNull();
        verifyNoInteractions(agentStateStore, agentProvider);
    }

    @Test
    void shouldOnlyDenyCallsNamedByCardNotOtherSameNameOrBatchTools() {
        AgentBlock completed = toolBlock("call-done", "done");
        completed.setResult("原始工具结果");
        AgentMessageDO message = confirmationWithTools(List.of("call-1", "call-2", "call-done", " "),
                toolBlock("call-1", "awaiting"), toolBlock("call-2", "awaiting"),
                toolBlock("call-3", "awaiting"), completed, toolBlock(" ", "awaiting"));

        service.settlePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004", false);

        assertThat(message.getBlocks()).extracting(AgentBlock::getStatus)
                .containsExactly("denied", "denied", "awaiting", "done", "awaiting", "denied");
        assertThat(completed.getResult()).isEqualTo("原始工具结果");
        verify(messageMapper).updateById(message);
    }

    @Test
    void shouldNotTreatApprovalAsToolSuccess() {
        AgentMessageDO message = confirmationWithTools(List.of("call-1"), toolBlock("call-1", "awaiting"));

        service.settlePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004", true);

        assertThat(message.getBlocks()).extracting(AgentBlock::getStatus).containsExactly("awaiting", "approved");
        assertThat(message.getMessageStatus()).isEqualTo("NORMAL");
        verify(messageMapper).updateById(message);
    }

    @Test
    void shouldExpireCardWithoutDenyingToolAndOnlyUpdateOnce() {
        AgentMessageDO message = confirmationWithTools(List.of("call-1"), toolBlock("call-1", "awaiting"));

        service.expirePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004");
        service.expirePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004");

        assertThat(message.getBlocks()).extracting(AgentBlock::getStatus).containsExactly("awaiting", "expired");
        assertThat(message.getMessageStatus()).isEqualTo("NORMAL");
        verify(messageMapper).updateById(message);
    }

    @Test
    void shouldRejectRepeatedDecisionWithoutAnotherUpdate() {
        AgentMessageDO message = confirmationWithTools(List.of("call-1"), toolBlock("call-1", "awaiting"));
        service.settlePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004", false);

        for (boolean approved : List.of(false, true)) {
            assertThatThrownBy(() -> service.settlePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004", approved))
                    .hasMessageContaining("已处理");
        }
        service.expirePendingConfirm(CONVERSATION_ID, USER_ID, "m-4004");

        assertThat(message.getBlocks()).extracting(AgentBlock::getStatus).containsExactly("denied", "denied");
        verify(messageMapper).updateById(message);
    }

    private AgentMessageDO confirmationWithTools(List<String> callIds, AgentBlock... tools) {
        AgentMessageDO message = pendingConfirmation();
        AgentBlock card = message.getBlocks().get(0);
        card.setCalls(callIds.stream().map(id -> AgentConfirmCall.builder()
                .toolCallId(id).name("leave_submit").build()).toList());
        List<AgentBlock> blocks = new ArrayList<>(List.of(tools));
        blocks.add(card);
        message.setBlocks(blocks);
        when(conversationMapper.selectOne(any())).thenReturn(existingConversation("原会话"));
        when(messageMapper.selectOne(any())).thenReturn(message);
        return message;
    }

    private static AgentBlock toolBlock(String toolCallId, String status) {
        return AgentBlock.builder().kind("tool").name("leave_submit")
                .toolCallId(toolCallId).status(status).build();
    }

    @Test
    void shouldCreateMissingConversationWithoutPurgingData() {
        when(conversationMapper.selectOne(any())).thenReturn(null);

        String title = service.touchConversation(CONVERSATION_ID, USER_ID, "  本轮提问  ");

        ArgumentCaptor<AgentConversationDO> conversationCaptor = ArgumentCaptor.forClass(AgentConversationDO.class);
        verify(conversationMapper).selectOne(any());
        verify(conversationMapper).insert(conversationCaptor.capture());
        AgentConversationDO conversation = conversationCaptor.getValue();
        assertThat(title).isEqualTo("本轮提问");
        assertThat(conversation.getConversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(conversation.getUserId()).isEqualTo(USER_ID);
        assertThat(conversation.getTitle()).isEqualTo(title);
        assertThat(conversation.getLastTime()).isNotNull();
        // 正常新建由聊天入口分配新 ID，无需清理状态、消息或缓存
        verifyNoMoreInteractions(conversationMapper);
        verifyNoInteractions(agentStateStore, messageMapper, agentProvider);
    }

    @Test
    void shouldTouchExistingConversationWithoutCreatingOrPurgingData() {
        AgentConversationDO existing = existingConversation("老会话");
        existing.setLastTime(new Date(0));
        when(conversationMapper.selectOne(any())).thenReturn(existing);

        String title = service.touchConversation(CONVERSATION_ID, USER_ID, "本轮提问");

        assertThat(title).isEqualTo("老会话");
        assertThat(existing.getLastTime()).isAfter(new Date(0));
        verify(conversationMapper).selectOne(any());
        verify(conversationMapper).updateById(existing);
        verifyNoMoreInteractions(conversationMapper);
        verifyNoInteractions(agentStateStore, messageMapper, agentProvider);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectPendingConfirmationCheckWhenConversationNotVisibleToUser() {
        // 聊天入口续聊前检查确认状态，不可见会话必须在此处拒绝
        when(conversationMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.hasPendingConfirm(CONVERSATION_ID, USER_ID))
                .isInstanceOf(ClientException.class)
                .hasMessage("会话不存在");

        ArgumentCaptor<LambdaQueryWrapper<AgentConversationDO>> queryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(conversationMapper).selectOne(queryCaptor.capture());
        LambdaQueryWrapper<AgentConversationDO> query = queryCaptor.getValue();
        assertThat(query.getSqlSegment()).contains("conversation_id", "user_id");
        assertThat(query.getParamNameValuePairs().values()).containsExactlyInAnyOrder(CONVERSATION_ID, USER_ID);
        verifyNoMoreInteractions(conversationMapper);
        verifyNoInteractions(agentStateStore, messageMapper, agentProvider);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldCheckPendingConfirmationForExistingConversation(boolean pending) {
        when(conversationMapper.selectOne(any())).thenReturn(existingConversation("老会话"));
        when(messageMapper.exists(any())).thenReturn(pending);

        assertThat(service.hasPendingConfirm(CONVERSATION_ID, USER_ID)).isEqualTo(pending);

        verify(conversationMapper).selectOne(any());
        verify(messageMapper).exists(any());
        verifyNoMoreInteractions(conversationMapper, messageMapper);
        verifyNoInteractions(agentStateStore, agentProvider);
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
    void shouldFailCreationOnDuplicateKeyWithoutResumingExistingConversation() {
        DuplicateKeyException conflict = new DuplicateKeyException("uk_agent_conversation_user");
        when(conversationMapper.selectOne(any())).thenReturn(null);
        when(conversationMapper.insert(any(AgentConversationDO.class)))
                .thenThrow(conflict);

        assertThatThrownBy(() -> service.touchConversation(CONVERSATION_ID, USER_ID, "本轮提问"))
                .isSameAs(conflict);

        verify(conversationMapper).selectOne(any());
        verify(conversationMapper).insert(any(AgentConversationDO.class));
        verifyNoMoreInteractions(conversationMapper);
        verifyNoInteractions(agentStateStore, messageMapper, agentProvider);
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

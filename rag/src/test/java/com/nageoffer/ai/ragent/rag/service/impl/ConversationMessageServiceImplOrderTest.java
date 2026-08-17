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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationSummaryMapper;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationMessageServiceImplOrderTest {

    private static final String CONVERSATION_ID = "conversation-1";
    private static final String USER_ID = "user-1";

    @Mock
    private ConversationMessageMapper conversationMessageMapper;

    @Mock
    private ConversationSummaryMapper conversationSummaryMapper;

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private MessageFeedbackService feedbackService;

    @InjectMocks
    private ConversationMessageServiceImpl conversationMessageService;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, ConversationMessageDO.class);
    }

    @SuppressWarnings("unchecked")
    private String captureOrderBySegment(ConversationMessageOrder order) {
        when(conversationMapper.selectOne(any())).thenReturn(new ConversationDO());
        when(conversationMessageMapper.selectList(any())).thenReturn(List.of());

        conversationMessageService.listMessages(CONVERSATION_ID, USER_ID, 6, order);

        ArgumentCaptor<LambdaQueryWrapper<ConversationMessageDO>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(conversationMessageMapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        int idx = sql.toUpperCase().indexOf("ORDER BY");
        return idx < 0 ? "" : sql.substring(idx);
    }

    @Test
    void descendingOrderUsesIdAsTieBreaker() {
        String orderBy = captureOrderBySegment(ConversationMessageOrder.DESC).toLowerCase();
        assertTrue(orderBy.matches(".*order by\\s+create_?time\\s+desc\\s*,\\s*id\\s+desc.*"),
                "Unexpected ORDER BY: " + orderBy);
    }

    @Test
    void ascendingOrderUsesIdAsTieBreaker() {
        String orderBy = captureOrderBySegment(ConversationMessageOrder.ASC).toLowerCase();
        assertTrue(orderBy.matches(".*order by\\s+create_?time\\s+asc\\s*,\\s*id\\s+asc.*"),
                "Unexpected ORDER BY: " + orderBy);
    }
}

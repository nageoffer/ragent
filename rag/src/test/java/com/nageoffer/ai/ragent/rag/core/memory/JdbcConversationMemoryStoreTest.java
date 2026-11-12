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

package com.nageoffer.ai.ragent.rag.core.memory;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.ConversationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 历史消息必须带着落库状态出来：改写阶段靠它把中断的半截答案与限流话术排除在指代消解之外
 * <p>
 * 状态只是附带信息，答题上下文仍拿全部历史，所以这里只校验状态本身不丢、不误判
 */
class JdbcConversationMemoryStoreTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final String USER_ID = "user-1";

    private final ConversationService conversationService = mock(ConversationService.class);
    private final ConversationMessageService conversationMessageService = mock(ConversationMessageService.class);

    @Test
    void carriesMessageStatusFromDatabase() {
        List<ChatMessage> history = loadHistoryOf(
                record("user", "差旅报销走什么流程", "NORMAL"),
                record("assistant", "先在 OA 提交申请单", "NORMAL"),
                record("user", "需要哪些材料", "NORMAL"),
                record("assistant", "发票和审", "INTERRUPTED"),
                record("user", "多久能到账", "NORMAL"),
                record("assistant", "当前排队人数过多", "REJECTED"));

        assertThat(history).extracting(ChatMessage::getMessageStatus)
                .containsExactly(
                        ChatMessage.MessageStatus.NORMAL,
                        ChatMessage.MessageStatus.NORMAL,
                        ChatMessage.MessageStatus.NORMAL,
                        ChatMessage.MessageStatus.INTERRUPTED,
                        ChatMessage.MessageStatus.NORMAL,
                        ChatMessage.MessageStatus.REJECTED);
    }

    private List<ChatMessage> loadHistoryOf(ConversationMessageVO... records) {
        when(conversationMessageService.listMessages(anyString(), anyString(), anyInt(), any(ConversationMessageOrder.class)))
                .thenReturn(List.of(records));

        JdbcConversationMemoryStore store = new JdbcConversationMemoryStore(
                conversationService, conversationMessageService, new MemoryProperties());
        return store.loadHistory(CONVERSATION_ID, USER_ID);
    }

    private static ConversationMessageVO record(String role, String content, String status) {
        return ConversationMessageVO.builder()
                .role(role)
                .content(content)
                .messageStatus(status)
                .build();
    }
}

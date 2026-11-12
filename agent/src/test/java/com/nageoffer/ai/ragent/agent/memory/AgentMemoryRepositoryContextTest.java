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

package com.nageoffer.ai.ragent.agent.memory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryCursorMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 仲裁素材的取数边界：同一段对话，从上一条已整理的用户发言（含其后的助手回复）起，到本批最后一条用户发言为止
 * 本批发言本身不进背景，留下的是理解它们所需的其余消息
 */
class AgentMemoryRepositoryContextTest {

    private AgentMessageMapper messages;
    private AgentMemoryRepository repository;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentMessageDO.class);
        messages = mock(AgentMessageMapper.class);
        repository = new AgentMemoryRepository(mock(AgentMemoryMapper.class), mock(AgentMemoryCursorMapper.class),
                messages, new AgentMemoryProperties());
    }

    /**
     * 上一轮完整问答作背景：上一条用户原话连同它得到的助手回复都在，止于本批最后一条用户发言，本批那两条自己不在内
     */
    @Test
    void shouldLoadPreviousRoundAndAllAssistantSegments() {
        AgentMessageDO previous = message("10", "A", "旧用户");
        List<AgentMessageDO> dialogue = List.of(previous, message("11", "A", "给父亲选手机"),
                message("12", "A", "更看重大屏吗？"), message("20", "A", "是的"),
                message("21", "A", "第一段正文"), message("22", "A", "第二段正文"),
                message("30", "A", "确认"));
        when(messages.selectOne(any())).thenReturn(previous);
        when(messages.selectList(any())).thenReturn(dialogue);

        assertThat(repository.loadBackground("u-1", List.of(dialogue.get(3), dialogue.get(6))))
                .containsExactly(dialogue.get(0), dialogue.get(1), dialogue.get(2), dialogue.get(4), dialogue.get(5));

        ArgumentCaptor<LambdaQueryWrapper<AgentMessageDO>> previousQuery = captor();
        ArgumentCaptor<LambdaQueryWrapper<AgentMessageDO>> dialogueQuery = captor();
        verify(messages).selectOne(previousQuery.capture());
        verify(messages).selectList(dialogueQuery.capture());
        assertThat(sql(previousQuery.getValue())).contains("role = 'user'", "id < '20'", "LIMIT 1");
        assertThat(sql(dialogueQuery.getValue())).contains("id >= '10'", "id <= '30'", "ORDER BY id ASC",
                "role IN ('user','assistant')", "user_id = 'u-1'", "conversation_id = 'A'")
                .doesNotContain("LIMIT");
    }

    /**
     * 本批就是对话首条：没有更早的用户发言可取，减掉本批自己之后背景为空
     */
    @Test
    void firstUserNeedsNoOlderUserOrFollowingAssistant() {
        when(messages.selectOne(any())).thenReturn(null);
        AgentMessageDO first = message("10", "A", "我一直戴眼镜");
        when(messages.selectList(any())).thenReturn(List.of(first));

        assertThat(repository.loadBackground("u-1", List.of(first))).isEmpty();

        ArgumentCaptor<LambdaQueryWrapper<AgentMessageDO>> query = captor();
        verify(messages).selectList(query.capture());
        assertThat(sql(query.getValue())).contains("id <= '10'").doesNotContain("id >");
    }

    /**
     * A→B→A：A 只按 A1~A2 的区间读一次，B 单独读，合起来按消息先后排；三条本批发言都不进背景
     */
    @Test
    void shouldReadEachConversationOnceAndMergeInMessageOrder() {
        AgentMessageDO a1 = message("1900000000000000010", "A", "我想换个手机");
        AgentMessageDO aReply = message("1900000000000000011", "A", "喜欢大一点还是小一点？");
        AgentMessageDO b0 = message("1900000000000000015", "B", "之前看过的平板");
        AgentMessageDO bReply = message("1900000000000000016", "B", "这几款都合适");
        AgentMessageDO b1 = message("1900000000000000020", "B", "想给自己买个平板");
        AgentMessageDO aReply2 = message("1900000000000000025", "A", "小屏的有这几款");
        AgentMessageDO a2 = message("1900000000000000030", "A", "还是要小一点的");
        when(messages.selectOne(any())).thenReturn(null);
        when(messages.selectList(any())).thenReturn(List.of(a1, aReply, aReply2, a2), List.of(b0, bReply, b1));

        // A 的 aReply2 晚于 B 的两条，按 id 合并才排得对
        assertThat(repository.loadBackground("u-1", List.of(a1, b1, a2)))
                .containsExactly(aReply, b0, bReply, aReply2);

        ArgumentCaptor<LambdaQueryWrapper<AgentMessageDO>> queries = captor();
        verify(messages, times(2)).selectList(queries.capture());
        assertThat(sql(queries.getAllValues().get(0))).contains("conversation_id = 'A'", "id <= '1900000000000000030'");
        assertThat(sql(queries.getAllValues().get(1))).contains("conversation_id = 'B'", "id <= '1900000000000000020'");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<LambdaQueryWrapper<AgentMessageDO>> captor() {
        return ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
    }

    private String sql(LambdaQueryWrapper<AgentMessageDO> query) {
        String sql = query.getSqlSegment();
        for (Map.Entry<String, Object> parameter : query.getParamNameValuePairs().entrySet()) {
            sql = sql.replace("#{ew.paramNameValuePairs." + parameter.getKey() + "}", "'" + parameter.getValue() + "'");
        }
        return sql;
    }

    private AgentMessageDO message(String id, String conversationId, String content) {
        return AgentMessageDO.builder().id(id).conversationId(conversationId).userId("u-1").role("user").content(content).build();
    }
}

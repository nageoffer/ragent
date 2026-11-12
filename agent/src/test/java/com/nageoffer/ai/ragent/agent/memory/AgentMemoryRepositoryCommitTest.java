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

import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryCursorMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.enums.AgentMemorySourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 两个提交入口共用一份写入：主动整理只写记忆不碰游标，后台先按读取时的位置推进游标再写；条件更新没命中整批抛出回滚
 */
class AgentMemoryRepositoryCommitTest {

    private static final String USER_ID = "u-1";
    private static final String CURSOR = "1900000000000000001";
    private static final String NEXT = "1900000000000000009";
    private static final String SOURCE_ID = "1900000000000000008";
    private static final Date EXPRESSED_AT = new Date(8000);

    private AgentMemoryMapper memoryMapper;
    private AgentMemoryCursorMapper cursorMapper;
    private AgentMemoryRepository repository;

    @BeforeEach
    void setUp() {
        memoryMapper = mock(AgentMemoryMapper.class);
        cursorMapper = mock(AgentMemoryCursorMapper.class);
        repository = new AgentMemoryRepository(memoryMapper, cursorMapper,
                mock(AgentMessageMapper.class), new AgentMemoryProperties());
        when(cursorMapper.advance(USER_ID, CURSOR, NEXT)).thenReturn(1);
        when(memoryMapper.insert(any(AgentMemoryDO.class))).thenReturn(1);
    }

    /**
     * 主动整理：按顺序写完全部变更，新行带上依据的用户消息与表达时间，不读也不推后台游标
     */
    @Test
    void flushShouldApplyEveryChangeWithoutTouchingCursor() {
        when(memoryMapper.invalidate(USER_ID, "m-2")).thenReturn(1);
        when(memoryMapper.invalidate(USER_ID, "m-3")).thenReturn(1);
        when(memoryMapper.updateContent(USER_ID, "m-1", "用户住在南京", "FLUSH", SOURCE_ID, EXPRESSED_AT))
                .thenReturn(1);

        repository.commitFlush(USER_ID, edit(List.of("m-2", "m-3"),
                List.of(new AgentMemoryEdit.Update("m-1", "用户住在南京", SOURCE_ID, EXPRESSED_AT)),
                List.of(new AgentMemoryEdit.Addition("用户喜欢咖啡", SOURCE_ID, EXPRESSED_AT))));

        verifyNoInteractions(cursorMapper);
        InOrder order = inOrder(memoryMapper);
        order.verify(memoryMapper).invalidate(USER_ID, "m-2");
        order.verify(memoryMapper).invalidate(USER_ID, "m-3");
        // 修改在原行上做，不插新行
        order.verify(memoryMapper).updateContent(USER_ID, "m-1", "用户住在南京", "FLUSH", SOURCE_ID, EXPRESSED_AT);
        ArgumentCaptor<AgentMemoryDO> inserted = ArgumentCaptor.forClass(AgentMemoryDO.class);
        verify(memoryMapper, times(1)).insert(inserted.capture());
        assertThat(inserted.getAllValues()).extracting(AgentMemoryDO::getContent).containsExactly("用户喜欢咖啡");
        assertThat(inserted.getAllValues()).allSatisfy(row -> {
            assertThat(row.getSourceType()).isEqualTo(AgentMemorySourceType.FLUSH.name());
            assertThat(row.getSourceMessageId()).isEqualTo(SOURCE_ID);
            assertThat(row.getExpressedAt()).isEqualTo(EXPRESSED_AT);
        });
    }

    /**
     * 后台：游标已被别的处理推过，本批是按旧状态判的，一行记忆都不许动
     */
    @Test
    void backgroundShouldWriteNothingWhenCursorHasMoved() {
        when(cursorMapper.advance(USER_ID, CURSOR, NEXT)).thenReturn(0);

        boolean committed = repository.commitBackground(USER_ID, CURSOR, NEXT,
                edit(List.of("m-1"), List.of(), List.of(new AgentMemoryEdit.Addition("用户住在南京", SOURCE_ID, EXPRESSED_AT))));

        assertThat(committed).isFalse();
        verifyNoInteractions(memoryMapper);
    }

    @Test
    void backgroundShouldAdvanceCursorBeforeWriting() {
        when(memoryMapper.invalidate(USER_ID, "m-1")).thenReturn(1);

        boolean committed = repository.commitBackground(USER_ID, CURSOR, NEXT,
                edit(List.of("m-1"), List.of(), List.of(new AgentMemoryEdit.Addition("用户住在南京", SOURCE_ID, EXPRESSED_AT))));

        assertThat(committed).isTrue();
        InOrder order = inOrder(cursorMapper, memoryMapper);
        order.verify(cursorMapper).advance(USER_ID, CURSOR, NEXT);
        order.verify(memoryMapper).invalidate(USER_ID, "m-1");
        ArgumentCaptor<AgentMemoryDO> inserted = ArgumentCaptor.forClass(AgentMemoryDO.class);
        order.verify(memoryMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getSourceType()).isEqualTo(AgentMemorySourceType.BACKGROUND.name());
    }

    /**
     * 条件更新落空说明目标在读取后被改过，必须抛出让整批回滚
     */
    @Test
    void shouldThrowWhenConditionalUpdateMisses() {
        when(memoryMapper.invalidate(USER_ID, "m-2")).thenReturn(0);

        assertThatThrownBy(() -> repository.commitFlush(USER_ID, edit(List.of("m-2"), List.of(),
                List.of(new AgentMemoryEdit.Addition("用户戴眼镜", SOURCE_ID, EXPRESSED_AT)))))
                .isInstanceOf(IllegalStateException.class);
        verify(memoryMapper, never()).insert(any(AgentMemoryDO.class));
    }

    private static AgentMemoryEdit edit(List<String> deleteIds, List<AgentMemoryEdit.Update> updates,
                                        List<AgentMemoryEdit.Addition> additions) {
        return new AgentMemoryEdit(deleteIds, updates, additions, List.of());
    }
}

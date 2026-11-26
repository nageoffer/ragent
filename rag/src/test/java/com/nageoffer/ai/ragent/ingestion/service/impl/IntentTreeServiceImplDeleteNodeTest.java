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

import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentTreeServiceImplDeleteNodeTest {

    private final IntentNodeMapper intentNodeMapper = mock(IntentNodeMapper.class);
    private final KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
    private final IntentTreeCacheManager intentTreeCacheManager = mock(IntentTreeCacheManager.class);
    private final BizChangeLogContext bizChangeLogContext = mock(BizChangeLogContext.class);

    private final IntentTreeServiceImpl intentTreeService = new IntentTreeServiceImpl(
            intentNodeMapper, knowledgeBaseMapper, intentTreeCacheManager, bizChangeLogContext);

    @Test
    void deleteNodeShouldRejectNodeWithChildren() {
        when(intentNodeMapper.selectById("1001")).thenReturn(node("1001", "kb-root", "根节点", null));
        when(intentNodeMapper.selectList(any()))
                .thenReturn(List.of(node("1002", "kb-root-child", "子节点", "kb-root")));

        assertThrows(ClientException.class, () -> intentTreeService.deleteNode("1001"));

        verify(intentNodeMapper, never()).deleteById(anyString());
        verify(intentTreeCacheManager, never()).clearIntentTreeCache();
    }

    @Test
    void deleteNodeShouldSucceedForLeafNode() {
        when(intentNodeMapper.selectById("2001")).thenReturn(node("2001", "kb-leaf", "叶子节点", "kb-root"));
        when(intentNodeMapper.selectList(any())).thenReturn(List.of());

        intentTreeService.deleteNode("2001");

        verify(intentNodeMapper).deleteById("2001");
        verify(intentTreeCacheManager).clearIntentTreeCache();
    }

    private static IntentNodeDO node(String id, String intentCode, String name, String parentCode) {
        return IntentNodeDO.builder()
                .id(id)
                .intentCode(intentCode)
                .name(name)
                .parentCode(parentCode)
                .deleted(0)
                .build();
    }
}

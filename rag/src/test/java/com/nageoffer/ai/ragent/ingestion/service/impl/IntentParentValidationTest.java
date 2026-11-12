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

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeUpdateRequest;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentParentValidationTest {

    private final IntentNodeMapper mapper = mock(IntentNodeMapper.class);
    private final IntentTreeCacheManager cache = mock(IntentTreeCacheManager.class);
    private final Map<String, IntentNodeDO> storedNodes = new LinkedHashMap<>();
    private final IntentTreeServiceImpl service = new IntentTreeServiceImpl(mapper,
            mock(KnowledgeBaseMapper.class), cache, mock(BizChangeLogContext.class));

    IntentParentValidationTest() {
        addNode("root", null);
        addNode("child", "root");
        addNode("grandchild", "child");
        addNode("other", null);
        when(mapper.selectById(anyString())).thenAnswer(invocation -> copy(storedNodes.get(invocation.getArgument(0))));
        when(mapper.selectList(any(Wrapper.class))).thenAnswer(invocation -> storedNodes.values().stream()
                .filter(node -> node.getDeleted() == 0).map(this::copy).toList());
        doAnswer(invocation -> {
            IntentNodeDO node = invocation.getArgument(0);
            storedNodes.put(node.getId(), copy(node));
            return 1;
        }).when(mapper).updateById(any(IntentNodeDO.class));
        doAnswer(invocation -> {
            IntentNodeDO node = invocation.getArgument(0);
            node.setId(node.getIntentCode());
            storedNodes.put(node.getId(), copy(node));
            return 1;
        }).when(mapper).insert(any(IntentNodeDO.class));
    }

    @Test
    void rejectsMovingNodeUnderItself() {
        assertRejectedMove("root", "root");
    }

    @Test
    void rejectsMovingParentUnderItsChildWithoutHidingTheTree() {
        assertRejectedMove("root", "child");

        var root = service.getFullTree().stream().filter(node -> node.getIntentCode().equals("root"))
                .findFirst().orElseThrow();
        assertEquals("child", root.getChildren().get(0).getIntentCode());
        assertEquals("grandchild", root.getChildren().get(0).getChildren().get(0).getIntentCode());
    }

    @Test
    void rejectsMovingNodeUnderADistantDescendant() {
        assertRejectedMove("root", "grandchild");
    }

    @Test
    void rejectsMissingParent() {
        assertRejectedMove("child", "missing");
    }

    @Test
    void rejectsBlankParentInsteadOfCreatingAnInvisibleRoot() {
        assertRejectedMove("child", "");
    }

    @Test
    void rejectsDeletedParent() {
        storedNodes.get("other").setDeleted(1);

        assertRejectedMove("child", "other");
    }

    @Test
    void rejectsParentWhoseExistingAncestryContainsACycle() {
        addNode("cycle-a", "cycle-b");
        addNode("cycle-b", "cycle-a");

        assertRejectedMove("child", "cycle-a");
    }

    @Test
    void rejectsParentWhoseAncestorIsMissing() {
        storedNodes.get("other").setParentCode("missing");

        assertRejectedMove("child", "other");
    }

    @Test
    void movesSubtreeToAnotherRoot() {
        service.updateNode("child", IntentNodeUpdateRequest.builder().parentCode("other").build());

        assertEquals("other", storedNodes.get("child").getParentCode());
        assertEquals("child", storedNodes.get("grandchild").getParentCode());
        verify(cache).clearIntentTreeCache();
    }

    @Test
    void creatingNodeRejectsSelfParent() {
        assertRejectedCreate("new-node", "new-node");
    }

    @Test
    void creatingNodeRejectsMissingParent() {
        assertRejectedCreate("new-node", "missing");
    }

    @Test
    void createsNodeUnderExistingParent() {
        String id = service.createNode(createRequest("new-node", "child"));

        assertEquals("child", storedNodes.get(id).getParentCode());
        verify(cache).clearIntentTreeCache();
    }

    @Test
    void createsRootWithoutParent() {
        String id = service.createNode(createRequest("new-node", null));

        assertNull(storedNodes.get(id).getParentCode());
    }

    private void assertRejectedMove(String nodeId, String parentCode) {
        String previousParent = storedNodes.get(nodeId).getParentCode();

        ClientException error = assertThrows(ClientException.class,
                () -> service.updateNode(nodeId, IntentNodeUpdateRequest.builder().parentCode(parentCode).build()));

        assertTrue(error.getMessage().contains("父节点"));
        assertEquals(previousParent, storedNodes.get(nodeId).getParentCode());
        verify(mapper, never()).updateById(any(IntentNodeDO.class));
        verify(cache, never()).clearIntentTreeCache();
    }

    private void assertRejectedCreate(String intentCode, String parentCode) {
        assertThrows(ClientException.class, () -> service.createNode(createRequest(intentCode, parentCode)));
        verify(mapper, never()).insert(any(IntentNodeDO.class));
        verify(cache, never()).clearIntentTreeCache();
    }

    private IntentNodeCreateRequest createRequest(String intentCode, String parentCode) {
        return IntentNodeCreateRequest.builder().intentCode(intentCode).name(intentCode)
                .kind(1).level(0).parentCode(parentCode).build();
    }

    private void addNode(String code, String parentCode) {
        storedNodes.put(code, IntentNodeDO.builder().id(code).intentCode(code).name(code)
                .parentCode(parentCode).level(0).kind(1).deleted(0).build());
    }

    private IntentNodeDO copy(IntentNodeDO node) {
        return node == null ? null : BeanUtil.copyProperties(node, IntentNodeDO.class);
    }
}

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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeUpdateRequest;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentRootMoveTest {

    private static final MybatisConfiguration CONFIGURATION = new MybatisConfiguration();

    @BeforeAll
    static void registerMapperMetadata() {
        new MybatisSqlSessionFactoryBuilder().build(CONFIGURATION);
        CONFIGURATION.addMapper(IntentNodeMapper.class);
    }

    @Test
    void explicitNullParentMovesChildToRoot() {
        IntentNodeDO saved = updateWithJson("{\"parentCode\":null}");

        assertNull(saved.getParentCode(), "The ROOT option sends parentCode:null");
    }

    @Test
    void omittedParentPreservesCurrentParent() {
        IntentNodeDO saved = updateWithJson("{\"name\":\"renamed\"}");

        assertEquals("group", saved.getParentCode());
        assertEquals("renamed", saved.getName());
    }

    @Test
    void nonNullParentStillMovesToAnotherBranch() {
        IntentNodeDO saved = updateWithJson("{\"parentCode\":\"other-group\"}");

        assertEquals("other-group", saved.getParentCode());
    }

    @Test
    void updateByIdRetainsDefaultNonNullParentUpdate() {
        IntentNodeDO renamed = IntentNodeDO.builder().id("2").parentCode("new-parent").build();

        String sql = CONFIGURATION.getMappedStatement(IntentNodeMapper.class.getName() + ".updateById")
                .getBoundSql(Map.of("et", renamed)).getSql();

        assertTrue(sql.contains("parent_code"), "Non-null entity updates must still write the parent, SQL: " + sql);
    }

    private IntentNodeDO updateWithJson(String json) {
        IntentNodeMapper mapper = mock(IntentNodeMapper.class);
        IntentNodeDO child = IntentNodeDO.builder().id("2").intentCode("child").name("child")
                .parentCode("group").level(1).kind(0).deleted(0).build();
        when(mapper.selectById("2")).thenReturn(child);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                IntentNodeDO.builder().id("3").intentCode("other-group").deleted(0).build()));
        IntentTreeServiceImpl service = new IntentTreeServiceImpl(mapper, mock(KnowledgeBaseMapper.class),
                mock(IntentTreeCacheManager.class), mock(BizChangeLogContext.class));
        // 与 Spring Boot 4 HTTP 请求使用的 Jackson 3 保持一致。
        IntentNodeUpdateRequest request = JsonMapper.builder().build()
                .readValue(json, IntentNodeUpdateRequest.class);

        service.updateNode("2", request);

        ArgumentCaptor<IntentNodeDO> updated = ArgumentCaptor.forClass(IntentNodeDO.class);
        ArgumentCaptor<Wrapper> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(updated.capture(), wrapper.capture());
        String sql = CONFIGURATION.getMappedStatement(IntentNodeMapper.class.getName() + ".update")
                .getBoundSql(Map.of("et", updated.getValue(), "ew", wrapper.getValue())).getSql();
        assertEquals(1, sql.split("parent_code\\s*=", -1).length - 1,
                "The actual service update must assign parent_code exactly once, SQL: " + sql);
        return updated.getValue();
    }
}

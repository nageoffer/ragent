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
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeUpdateRequest;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 仅在显式指定独立的 intent_parent_test 数据库时运行，不依赖应用数据库。
 * 设置 RAGENT_PARENT_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:端口/intent_parent_test，
 * 使用专用测试凭据 postgres / intent-parent-test；每个用例会重建测试表。
 */
@EnabledIfEnvironmentVariable(named = "RAGENT_PARENT_TEST_JDBC_URL", matches = ".+")
class IntentRootMovePostgresTest {

    private final IntentTreeCacheManager cache = mock(IntentTreeCacheManager.class);
    private JdbcTemplate jdbc;
    private IntentTreeServiceImpl service;

    @BeforeEach
    void setUp() {
        String url = System.getenv("RAGENT_PARENT_TEST_JDBC_URL");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/intent_parent_test"));
        var dataSource = new DriverManagerDataSource(url, "postgres", "intent-parent-test");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS t_intent_node");
        jdbc.execute("""
                CREATE TABLE t_intent_node (
                    id TEXT PRIMARY KEY, kb_id TEXT, intent_code TEXT UNIQUE, name TEXT,
                    level INTEGER, parent_code TEXT, description TEXT, examples TEXT,
                    collection_name TEXT, collection_names JSONB, mcp_tool_id TEXT,
                    require_confirm INTEGER, top_k INTEGER, kind INTEGER, sort_order INTEGER,
                    prompt_snippet TEXT, prompt_template TEXT, enabled INTEGER,
                    create_by TEXT, update_by TEXT, create_time TIMESTAMP, update_time TIMESTAMP,
                    deleted INTEGER DEFAULT 0
                )
                """);
        jdbc.update("""
                INSERT INTO t_intent_node (id, intent_code, name, parent_code, kind, level, enabled)
                VALUES ('1', 'a', 'A', NULL, 1, 0, 0),
                       ('2', 'b', 'B', 'a', 1, 1, 0),
                       ('3', 'c', 'C', 'b', 1, 2, 0)
                """);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        configuration.addMapper(IntentNodeMapper.class);
        var mapper = new SqlSessionTemplate(factory).getMapper(IntentNodeMapper.class);
        service = new IntentTreeServiceImpl(mapper, mock(KnowledgeBaseMapper.class), cache,
                mock(BizChangeLogContext.class));
    }

    @Test
    void movingChildToRootPersistsNullAndKeepsGrandchildAttached() {
        var request = JsonMapper.builder().build()
                .readValue("{\"parentCode\":null}", IntentNodeUpdateRequest.class);

        service.updateNode("2", request);

        assertNull(parentOf("2"));
        assertEquals("b", parentOf("3"));
        var movedRoot = service.getFullTree().stream()
                .filter(node -> node.getIntentCode().equals("b")).findFirst().orElseThrow();
        assertEquals("c", movedRoot.getChildren().get(0).getIntentCode());
        verify(cache).clearIntentTreeCache();
    }

    @Test
    void movingGrandchildToRootLeavesItsAncestorsUnchanged() {
        var request = JsonMapper.builder().build()
                .readValue("{\"parentCode\":null}", IntentNodeUpdateRequest.class);

        service.updateNode("3", request);

        assertNull(parentOf("1"));
        assertEquals("a", parentOf("2"));
        assertNull(parentOf("3"));
    }

    @Test
    void renamingChildWithoutParentFieldPreservesItsParent() {
        var request = JsonMapper.builder().build()
                .readValue("{\"name\":\"renamed\"}", IntentNodeUpdateRequest.class);

        service.updateNode("2", request);

        assertEquals("a", parentOf("2"));
        assertEquals("b", parentOf("3"));
        assertEquals("renamed", jdbc.queryForObject(
                "SELECT name FROM t_intent_node WHERE id = '2'", String.class));
    }

    @Test
    void movingToAnotherParentPersistsWithoutDuplicateColumnAssignment() {
        var request = JsonMapper.builder().build()
                .readValue("{\"parentCode\":\"a\"}", IntentNodeUpdateRequest.class);

        service.updateNode("3", request);

        assertEquals("a", parentOf("2"));
        assertEquals("a", parentOf("3"));
    }

    @Test
    void batchEnableAndDisablePreserveParentRelationships() {
        service.batchEnableNodes(List.of("2", "3"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_intent_node WHERE enabled = 1", Integer.class));
        assertEquals("a", parentOf("2"));
        assertEquals("b", parentOf("3"));

        service.batchDisableNodes(List.of("2", "3"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_intent_node WHERE enabled = 1", Integer.class));
        assertEquals("a", parentOf("2"));
        assertEquals("b", parentOf("3"));
    }

    private String parentOf(String id) {
        return jdbc.queryForObject("SELECT parent_code FROM t_intent_node WHERE id = ?", String.class, id);
    }
}

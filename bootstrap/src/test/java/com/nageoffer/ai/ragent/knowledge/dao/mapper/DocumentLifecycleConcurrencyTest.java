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

package com.nageoffer.ai.ragent.knowledge.dao.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用 Testcontainers PostgreSQL 验证分块/删除抢占及写入行锁的真实并发行为；无 Docker 时自动跳过。 */
@Testcontainers(disabledWithoutDocker = true)
class DocumentLifecycleConcurrencyTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS t_knowledge_chunk");
            statement.execute("DROP TABLE IF EXISTS t_knowledge_document");
            statement.execute("CREATE TABLE t_knowledge_document ("
                    + "id VARCHAR(20) PRIMARY KEY, status VARCHAR(16) NOT NULL, deleted SMALLINT NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE t_knowledge_chunk ("
                    + "id VARCHAR(20) PRIMARY KEY, doc_id VARCHAR(20) NOT NULL)");
            statement.execute("INSERT INTO t_knowledge_document(id, status, deleted) VALUES ('doc-1', 'pending', 0)");
        }
    }

    @Test
    void chunkAndDeleteClaimsHaveExactlyOneWinner() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> chunk = executor.submit(() -> claim("running", ready, start));
            Future<Integer> delete = executor.submit(() -> claim("deleting", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(chunk.get(5, TimeUnit.SECONDS) + delete.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(currentStatus()).isIn("running", "deleting");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void successfulWritePreventsWaitingRecoveryFromMarkingDocumentFailed() throws Exception {
        updateStatus("running");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection writer = connection()) {
            writer.setAutoCommit(false);
            assertThat(lockStatus(writer)).isEqualTo("running");

            Future<Integer> recovery = executor.submit(() -> transition("running", "failed"));
            assertThatThrownBy(() -> recovery.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            try (Statement statement = writer.createStatement()) {
                statement.executeUpdate("INSERT INTO t_knowledge_chunk(id, doc_id) VALUES ('chunk-1', 'doc-1')");
                statement.executeUpdate("UPDATE t_knowledge_document SET status = 'success' WHERE id = 'doc-1'");
            }
            writer.commit();

            assertThat(recovery.get(5, TimeUnit.SECONDS)).isZero();
            assertThat(currentStatus()).isEqualTo("success");
            assertThat(transition("success", "deleting")).isEqualTo(1);
            deleteChunks();
            assertThat(chunkCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleWriterObservesDeletingAndDoesNotInsertChunks() throws Exception {
        updateStatus("deleting");

        try (Connection writer = connection()) {
            writer.setAutoCommit(false);
            assertThat(lockStatus(writer)).isEqualTo("deleting");
            writer.rollback();
        }

        assertThat(chunkCount()).isZero();
    }

    private int claim(String target, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            int updated;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE t_knowledge_document SET status = ? "
                            + "WHERE id = 'doc-1' AND deleted = 0 AND status IN ('pending', 'failed', 'success')")) {
                statement.setString(1, target);
                updated = statement.executeUpdate();
            }
            connection.commit();
            return updated;
        }
    }

    private int transition(String source, String target) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE t_knowledge_document SET status = ? WHERE id = 'doc-1' AND status = ?")) {
            statement.setString(1, target);
            statement.setString(2, source);
            return statement.executeUpdate();
        }
    }

    private void updateStatus(String status) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE t_knowledge_document SET status = ? WHERE id = 'doc-1'")) {
            statement.setString(1, status);
            statement.executeUpdate();
        }
    }

    private String lockStatus(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM t_knowledge_document WHERE id = 'doc-1' FOR UPDATE");
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private String currentStatus() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT status FROM t_knowledge_document WHERE id = 'doc-1'")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private void deleteChunks() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM t_knowledge_chunk WHERE doc_id = 'doc-1'");
        }
    }

    private int chunkCount() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM t_knowledge_chunk WHERE doc_id = 'doc-1'")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}

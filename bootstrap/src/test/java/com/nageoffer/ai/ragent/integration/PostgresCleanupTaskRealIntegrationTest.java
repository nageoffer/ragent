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

package com.nageoffer.ai.ragent.integration;

import com.nageoffer.ai.ragent.knowledge.enums.CleanupTaskStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("real-it")
@EnabledIfSystemProperty(named = RealIntegrationTestSupport.ENABLED_PROPERTY, matches = "true")
class PostgresCleanupTaskRealIntegrationTest {

    @Test
    void fileCleanupTaskClaimIsAtomicAgainstRealPostgres() throws Exception {
        String url = RealIntegrationTestSupport.value(
                "spring.datasource.url",
                "RAGENT_POSTGRES_URL",
                "jdbc:postgresql://" + RealIntegrationTestSupport.DEFAULT_TAILSCALE_HOST + ":5432/ragent?client_encoding=UTF8");
        String username = RealIntegrationTestSupport.value(
                "spring.datasource.username",
                "RAGENT_POSTGRES_USERNAME",
                "postgres");
        String password = RealIntegrationTestSupport.value(
                "spring.datasource.password",
                "RAGENT_POSTGRES_PASSWORD",
                System.getenv("PGPASSWORD"));
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Missing real PostgreSQL password: set -Dspring.datasource.password, RAGENT_POSTGRES_PASSWORD, or PGPASSWORD");
        }

        String id = "rit" + Long.toHexString(System.nanoTime());
        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            assertCleanupTableExists(conn);
            insertPendingFileCleanupTask(conn, id);
            try {
                Timestamp leaseUntil = Timestamp.from(Instant.now().plusSeconds(60));

                assertThat(claimFileCleanupTask(conn, id, leaseUntil)).isEqualTo(1);
                assertThat(claimFileCleanupTask(conn, id, leaseUntil)).isZero();
                assertThat(selectStatus(conn, id)).isEqualTo(CleanupTaskStatus.RUNNING.getCode());

                expireLease(conn, id);
                assertThat(recoverExpiredFileCleanupTask(conn, id)).isEqualTo(1);
                assertThat(selectStatus(conn, id)).isEqualTo(CleanupTaskStatus.PENDING.getCode());
            } finally {
                deleteFileCleanupTask(conn, id);
            }
        }
    }

    private void assertCleanupTableExists(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name = 't_file_cleanup_task'
                """);
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    private void insertPendingFileCleanupTask(Connection conn, String id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO t_file_cleanup_task (id, file_url, status, retry_count, create_time, update_time)
                VALUES (?, ?, ?, 0, NOW(), NOW())
                """)) {
            ps.setString(1, id);
            ps.setString(2, "s3://ragent-real-it/" + id + ".txt");
            ps.setString(3, CleanupTaskStatus.PENDING.getCode());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private int claimFileCleanupTask(Connection conn, String id, Timestamp leaseUntil) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE t_file_cleanup_task
                SET status = ?, next_retry_time = ?, update_time = NOW()
                WHERE id = ? AND status = ?
                """)) {
            ps.setString(1, CleanupTaskStatus.RUNNING.getCode());
            ps.setTimestamp(2, leaseUntil);
            ps.setString(3, id);
            ps.setString(4, CleanupTaskStatus.PENDING.getCode());
            return ps.executeUpdate();
        }
    }

    private void expireLease(Connection conn, String id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE t_file_cleanup_task
                SET next_retry_time = NOW() - INTERVAL '1 second', update_time = NOW()
                WHERE id = ?
                """)) {
            ps.setString(1, id);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private int recoverExpiredFileCleanupTask(Connection conn, String id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE t_file_cleanup_task
                SET status = ?, update_time = NOW()
                WHERE id = ? AND status = ? AND next_retry_time <= NOW()
                """)) {
            ps.setString(1, CleanupTaskStatus.PENDING.getCode());
            ps.setString(2, id);
            ps.setString(3, CleanupTaskStatus.RUNNING.getCode());
            return ps.executeUpdate();
        }
    }

    private String selectStatus(Connection conn, String id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT status
                FROM t_file_cleanup_task
                WHERE id = ?
                """)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private void deleteFileCleanupTask(Connection conn, String id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM t_file_cleanup_task WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }
}

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

package com.nageoffer.ai.ragent.test;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.regex.Pattern;

public final class LocalPostgresTestDatabase implements AutoCloseable {

    private static final String PREFIX = "ragent_document_version_";
    private static final Pattern SAFE_DATABASE_NAME = Pattern.compile(PREFIX + "[a-f0-9]{32}");

    private final String adminUrl = System.getProperty(
            "ragent.test.postgres.admin-url", "jdbc:postgresql://127.0.0.1:5432/postgres");
    private final String username = System.getProperty("ragent.test.postgres.username", "postgres");
    private final String password = System.getProperty("ragent.test.postgres.password", "postgres");
    private final String databaseName = PREFIX + UUID.randomUUID().toString().replace("-", "");
    private final String jdbcUrl = databaseUrl();
    private HikariDataSource dataSource;
    private boolean created;

    public HikariDataSource start() {
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + databaseName + "\"");
            created = true;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "无法连接本地 PostgreSQL 或创建临时数据库，请确认 127.0.0.1:5432 可用且 postgres 用户具有 CREATEDB 权限"
                            + "，可通过 ragent.test.postgres.admin-url/username/password 覆盖配置",
                    e);
        }

        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(8);
            config.setPoolName("ragent-document-version-test");
            dataSource = new HikariDataSource(config);
            createSchema();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
        return dataSource;
    }

    public HikariDataSource dataSource() {
        if (dataSource == null) {
            throw new IllegalStateException("临时数据库尚未启动");
        }
        return dataSource;
    }

    public void cleanBusinessTables() {
        execute("TRUNCATE t_knowledge_vector, t_knowledge_chunk, t_knowledge_document_schedule_exec, "
                + "t_knowledge_document_schedule, t_knowledge_document_chunk_log, t_knowledge_document");
    }

    private void createSchema() {
        try {
            execute("CREATE EXTENSION vector");
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "临时数据库无法创建 pgvector 扩展，请安装 PostgreSQL vector 扩展并授予 postgres 用户 CREATE 权限",
                    e);
        }
        execute("CREATE TABLE t_knowledge_document ("
                + "id VARCHAR(20) PRIMARY KEY, kb_id VARCHAR(20) NOT NULL, doc_name VARCHAR(256) NOT NULL, "
                + "enabled SMALLINT NOT NULL DEFAULT 1, chunk_count INTEGER DEFAULT 0, file_url VARCHAR(1024) NOT NULL, "
                + "file_type VARCHAR(16) NOT NULL, mime_type VARCHAR(128), file_size BIGINT, process_mode VARCHAR(16), "
                + "status VARCHAR(16) NOT NULL, document_version VARCHAR(20) NOT NULL, source_type VARCHAR(16), "
                + "source_location VARCHAR(1024), schedule_enabled SMALLINT, schedule_cron VARCHAR(64), "
                + "ingestion_spec JSONB, pipeline_id VARCHAR(20), created_by VARCHAR(20) NOT NULL, updated_by VARCHAR(20), "
                + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "deleted SMALLINT NOT NULL DEFAULT 0)");
        execute("CREATE TABLE t_knowledge_chunk ("
                + "id VARCHAR(20) PRIMARY KEY, kb_id VARCHAR(20) NOT NULL, doc_id VARCHAR(20) NOT NULL, "
                + "chunk_index INTEGER NOT NULL, content TEXT NOT NULL, content_hash VARCHAR(64), char_count INTEGER, "
                + "token_count INTEGER, embedding_text TEXT, enabled SMALLINT NOT NULL DEFAULT 1, created_by VARCHAR(20), "
                + "updated_by VARCHAR(20), create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, deleted SMALLINT NOT NULL DEFAULT 0)");
        execute("CREATE TABLE t_knowledge_vector (id VARCHAR(20) PRIMARY KEY, collection_name VARCHAR(128) NOT NULL, "
                + "content TEXT, metadata JSONB, embedding vector(3))");
        execute("CREATE TABLE t_knowledge_document_chunk_log (id VARCHAR(20) PRIMARY KEY, doc_id VARCHAR(20), status VARCHAR(16))");
        execute("CREATE TABLE t_knowledge_document_schedule (id VARCHAR(20) PRIMARY KEY, doc_id VARCHAR(20), kb_id VARCHAR(20), "
                + "enabled SMALLINT, next_run_time TIMESTAMP, lock_owner VARCHAR(64), lock_until TIMESTAMP)");
        execute("CREATE TABLE t_knowledge_document_schedule_exec (id VARCHAR(20) PRIMARY KEY, schedule_id VARCHAR(20), "
                + "doc_id VARCHAR(20), kb_id VARCHAR(20), status VARCHAR(16))");
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("初始化或清理临时 PostgreSQL 失败: " + sql, e);
        }
    }

    private String databaseUrl() {
        int query = adminUrl.indexOf('?');
        String suffix = query >= 0 ? adminUrl.substring(query) : "";
        String withoutQuery = query >= 0 ? adminUrl.substring(0, query) : adminUrl;
        int slash = withoutQuery.lastIndexOf('/');
        if (slash < "jdbc:postgresql://".length()) {
            throw new IllegalArgumentException("管理 URL 必须包含数据库名: " + adminUrl);
        }
        return withoutQuery.substring(0, slash + 1) + databaseName + suffix;
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
        if (!created) {
            return;
        }
        if (!SAFE_DATABASE_NAME.matcher(databaseName).matches()) {
            throw new IllegalStateException("拒绝删除不符合测试前缀规则的数据库: " + databaseName);
        }
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '"
                    + databaseName + "' AND pid <> pg_backend_pid()");
            statement.execute("DROP DATABASE \"" + databaseName + "\"");
            created = false;
        } catch (SQLException e) {
            throw new IllegalStateException("无法删除本次创建的临时 PostgreSQL 数据库: " + databaseName, e);
        }
    }
}

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

package com.nageoffer.ai.ragent.mcp.config.bit;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * 启动期建库建表
 * <p>
 * 表结构归本模块管、每次启动都跑，所以只能是非破坏性的：数据那半归 initializer 的 BizDataInitMain，
 * 它靠清空重灌实现幂等，放到启动路径上就成了「重启一次清空一次订单」
 */
@Slf4j
@RequiredArgsConstructor
public class BitSchemaInitializer {

    /**
     * 改了 bit-schema.sql 就把这个数加一
     */
    static final int SCHEMA_VERSION = 8;

    private static final String SCHEMA_SCRIPT = "db/bit-schema.sql";

    private static final String URL_PREFIX = "jdbc:postgresql://";

    /**
     * 库名要拼进 CREATE DATABASE，DDL 没法用占位符，只能先把取值范围卡死
     */
    private static final Pattern DATABASE_NAME = Pattern.compile("[A-Za-z0-9_]{1,63}");

    /**
     * 版本表本身不写在 bit-schema.sql 里：它是决定那个脚本跑不跑的闸门，得先于脚本存在
     */
    private static final String VERSION_TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS t_schema_version (
                version    INT PRIMARY KEY,
                applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )""";

    private final BitProperties.Datasource datasource;

    public void initialize() {
        ParsedUrl parsed = parseUrl(datasource.getUrl());
        ensureDatabase(parsed);
        applySchema(parsed);
    }

    /**
     * 库不存在就建，建库这一步必须 autocommit——CREATE DATABASE 在任何事务块里都直接报错
     */
    private void ensureDatabase(ParsedUrl parsed) {
        String maintenanceUrl = parsed.withDatabase(resolveMaintenanceDb());
        try (Connection connection = connect(maintenanceUrl)) {
            if (databaseExists(connection, parsed.database())) {
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE \"" + parsed.database() + "\"");
            }
            log.info("比特严选业务库已创建, database={}", parsed.database());
        } catch (SQLException e) {
            // 并发起两个进程时后到的那个会撞上，说明库已经有了
            if ("42P04".equals(e.getSQLState())) {
                return;
            }
            throw new IllegalStateException(explain(e, parsed), e);
        }
    }

    private boolean databaseExists(Connection connection, String database) throws SQLException {
        try (PreparedStatement statement = connection
                .prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            statement.setString(1, database);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    /**
     * 三分支：没记录就跑全量 DDL，版本相等就跳过，对不上直接拒绝启动
     * <p>
     * 丢掉 DROP + CREATE 之后要补回它挡住的那件事——结构改版而库里还是旧的，CREATE IF NOT EXISTS
     * 会静默跳过，报错最后以「某个字段不存在」的形式出现在离原因很远的地方
     */
    private void applySchema(ParsedUrl parsed) {
        try (Connection connection = connect(datasource.getUrl())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(VERSION_TABLE_DDL);
            }
            Integer current = readVersion(connection);
            if (current != null && current == SCHEMA_VERSION) {
                return;
            }
            if (current != null && current == 7) {
                connection.setAutoCommit(false);
                try {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new ClassPathResource("db/cart-reliability.sql"), StandardCharsets.UTF_8));
                    try (PreparedStatement statement = connection.prepareStatement("INSERT INTO t_schema_version(version) VALUES (?)")) {
                        statement.setInt(1, SCHEMA_VERSION);
                        statement.executeUpdate();
                    }
                    connection.commit();
                } catch (RuntimeException | SQLException e) {
                    connection.rollback();
                    throw e;
                } finally { connection.setAutoCommit(true); }
                return;
            }
            if (current != null) {
                throw new IllegalStateException(versionMismatch(current, parsed.database()));
            }
            createSchema(connection);
            log.info("比特严选业务库表结构已建, database={}, version={}", parsed.database(), SCHEMA_VERSION);
        } catch (SQLException e) {
            throw new IllegalStateException(explain(e, parsed), e);
        }
    }

    /**
     * 建表与写版本号同一个事务，PostgreSQL 的 DDL 可回滚，中途失败不会留下半套表配一条版本记录
     */
    private void createSchema(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try {
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource(SCHEMA_SCRIPT), StandardCharsets.UTF_8));
            try (PreparedStatement statement = connection
                    .prepareStatement("INSERT INTO t_schema_version (version) VALUES (?)")) {
                statement.setInt(1, SCHEMA_VERSION);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (RuntimeException | SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private Integer readVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT max(version) FROM t_schema_version")) {
            if (!resultSet.next()) {
                return null;
            }
            int version = resultSet.getInt(1);
            return resultSet.wasNull() ? null : version;
        }
    }

    private Connection connect(String url) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", StrUtil.emptyIfNull(datasource.getUsername()));
        properties.setProperty("password", StrUtil.emptyIfNull(datasource.getPassword()));
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("loginTimeout", "5");
        return DriverManager.getConnection(url, properties);
    }

    private String resolveMaintenanceDb() {
        String maintenanceDb = StrUtil.blankToDefault(datasource.getMaintenanceDb(), "postgres");
        if (!DATABASE_NAME.matcher(maintenanceDb).matches()) {
            throw new IllegalStateException("ragent.bit.datasource.maintenance-db 只允许字母数字下划线: " + maintenanceDb);
        }
        return maintenanceDb;
    }

    /**
     * 原生报错离原因太远，每一种都换成能照着做的下一步
     */
    static String explain(SQLException e, ParsedUrl parsed) {
        String state = StrUtil.emptyIfNull(e.getSQLState());
        if ("42501".equals(state)) {
            return "当前数据库账号无 CREATEDB 权限，请手动执行 createdb %s 后重启".formatted(parsed.database());
        }
        if (state.startsWith("08") || "28P01".equals(state) || "28000".equals(state)) {
            return ("连不上 PostgreSQL（%s），请启动数据库并确认 ragent.bit.datasource 的地址和账号密码")
                    .formatted(parsed.url());
        }
        return "比特严选业务库初始化失败: " + e.getMessage();
    }

    static String versionMismatch(int current, String database) {
        return ("业务库结构版本 %d，代码要求 %d。请核对 %s 的迁移脚本；"
                + "不要删除业务库，以免丢失购物车回执")
                .formatted(current, SCHEMA_VERSION, database);
    }

    /**
     * 从 JDBC URL 里拆出库名，建库时要换成维护库再连一次
     */
    static ParsedUrl parseUrl(String url) {
        if (!StrUtil.startWith(url, URL_PREFIX)) {
            throw new IllegalStateException("ragent.bit.datasource.url 必须是 " + URL_PREFIX + " 开头: " + url);
        }
        String rest = url.substring(URL_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            throw new IllegalStateException("ragent.bit.datasource.url 里取不到库名: " + url);
        }
        String tail = rest.substring(slash + 1);
        int question = tail.indexOf('?');
        String database = question < 0 ? tail : tail.substring(0, question);
        if (!DATABASE_NAME.matcher(database).matches()) {
            throw new IllegalStateException("业务库库名只允许字母数字下划线: " + database);
        }
        return new ParsedUrl(rest.substring(0, slash), database, question < 0 ? "" : tail.substring(question + 1));
    }

    record ParsedUrl(String authority, String database, String query) {

        String withDatabase(String other) {
            return URL_PREFIX + authority + "/" + other + (query.isEmpty() ? "" : "?" + query);
        }

        String url() {
            return withDatabase(database);
        }
    }
}

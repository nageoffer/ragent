/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Stream;

/** Executes fixed initialization SQL through JDBC without requiring a host-installed psql command. */
final class JdbcClient implements Closeable {

    private static final String POSTGRES_DRIVER_CLASS = "org.postgresql.Driver";

    private final InitializerConfig config;
    /**
     * 配置项前缀，平台库是 database，业务库是 biz-database
     * 两个库在同一个 PostgreSQL 实例上但互不知情，连接身份必须各配各的
     */
    private final String prefix;
    private URLClassLoader driverClassLoader;
    private Driver registeredDriver;
    private Path extractedDriver;

    JdbcClient(InitializerConfig config, String prefix) {
        this.config = config;
        this.prefix = prefix;
    }

    String description() {
        return "PostgreSQL JDBC " + config.require(prefix + ".host") + ":"
                + config.getInt(prefix + ".port", 5432) + "/" + config.require(prefix + ".name");
    }

    long queryLong(String sql) throws SQLException, IOException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(config.getInt(prefix + ".statement-timeout-seconds", 120));
            try (ResultSet result = statement.executeQuery(sql)) {
                if (!result.next()) {
                    throw new SQLException("JDBC 查询没有返回结果");
                }
                return result.getLong(1);
            }
        }
    }

    /**
     * 查询任意结果集并按字符串取值，NULL 取空串
     * 参数拼接由调用方经 literal 转义
     */
    List<List<String>> queryRows(String sql) throws SQLException, IOException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(config.getInt(prefix + ".statement-timeout-seconds", 120));
            try (ResultSet result = statement.executeQuery(sql)) {
                int columns = result.getMetaData().getColumnCount();
                List<List<String>> rows = new ArrayList<>();
                while (result.next()) {
                    List<String> row = new ArrayList<>(columns);
                    for (int index = 1; index <= columns; index++) {
                        String value = result.getString(index);
                        row.add(value == null ? "" : value);
                    }
                    rows.add(List.copyOf(row));
                }
                return List.copyOf(rows);
            }
        }
    }

    /**
     * SQL 字符串字面量转义，只处理单引号，不接受反斜杠转义关闭的会话参数
     */
    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    void executeScript(Path file) throws IOException, SQLException {
        executeScript(file, Map.of());
    }

    /**
     * 整份脚本一个事务，中途失败全回滚
     * variables 是 ${NAME} 占位替换，业务库种子数据要把订单挂到运行期才知道的平台用户 ID 上；
     * 替换值由调用方经 literal 转义，脚本里的占位符因此不带引号
     */
    void executeScript(Path file, Map<String, String> variables) throws IOException, SQLException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("SQL 文件不存在: " + file.toAbsolutePath());
        }
        String script = Files.readString(file, StandardCharsets.UTF_8);
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            script = script.replace("${" + variable.getKey() + "}", variable.getValue());
        }
        int unresolved = script.indexOf("${");
        if (unresolved >= 0) {
            throw new IllegalArgumentException("SQL 脚本存在未替换的占位符: " + file.getFileName()
                    + "，" + firstLine(script.substring(unresolved)));
        }
        List<String> statements = splitStatements(script);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("SQL 文件中没有可执行语句: " + file.toAbsolutePath());
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                for (String sql : statements) {
                    if (sql.stripLeading().startsWith("\\")) {
                        throw new IllegalArgumentException(file.getFileName() + " 不能包含 psql 专用命令: "
                                + firstLine(sql));
                    }
                    try (Statement statement = connection.createStatement()) {
                        statement.setQueryTimeout(config.getInt(prefix + ".statement-timeout-seconds", 120));
                        statement.execute(sql);
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    ex.addSuppressed(rollbackFailure);
                }
                throw ex;
            }
        }
    }

    private Connection openConnection() throws SQLException, IOException {
        ensureDriverLoaded();
        Properties properties = new Properties();
        properties.setProperty("user", config.require(prefix + ".username"));
        properties.setProperty("password", config.get(prefix + ".password", ""));
        properties.setProperty("connectTimeout",
                String.valueOf(config.getInt(prefix + ".connect-timeout-seconds", 5)));
        properties.setProperty("ApplicationName", "ragent-initializer");
        return DriverManager.getConnection(config.require(prefix + ".jdbc-url"), properties);
    }

    private synchronized void ensureDriverLoaded() throws SQLException, IOException {
        if (driverAvailable()) {
            return;
        }
        Path driverJar = locateDriverJar();
        if (driverJar == null) {
            throw new SQLException("未找到 PostgreSQL JDBC 驱动。请先构建 RagentAI，"
                    + "或通过 database.jdbc-driver-path 指定 postgresql-*.jar");
        }
        driverClassLoader = new URLClassLoader(new URL[]{driverJar.toUri().toURL()},
                ClassLoader.getSystemClassLoader());
        try {
            Class<?> driverType = Class.forName(POSTGRES_DRIVER_CLASS, true, driverClassLoader);
            Driver delegate = (Driver) driverType.getDeclaredConstructor().newInstance();
            registeredDriver = new DriverBridge(delegate);
            DriverManager.registerDriver(registeredDriver);
        } catch (ReflectiveOperationException | ClassCastException ex) {
            throw new SQLException("加载 PostgreSQL JDBC 驱动失败: " + driverJar, ex);
        }
    }

    private boolean driverAvailable() {
        try {
            Class.forName(POSTGRES_DRIVER_CLASS);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private Path locateDriverJar() throws IOException {
        String configured = config.get("database.jdbc-driver-path", "");
        if (!configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("database.jdbc-driver-path 不存在: " + path);
            }
            return path;
        }

        String userHome = System.getProperty("user.home", "");
        if (!userHome.isBlank()) {
            Path repository = Path.of(userHome, ".m2", "repository", "org", "postgresql", "postgresql");
            Path mavenDriver = newestFile(repository,
                    path -> isPostgresDriverJar(path.getFileName().toString()));
            if (mavenDriver != null) {
                return mavenDriver;
            }
        }
        return extractDriverFromBootstrapJar();
    }

    private Path extractDriverFromBootstrapJar() throws IOException {
        String applicationFileValue = config.get("application.config-resolved", "");
        if (applicationFileValue.isBlank()) {
            return null;
        }
        Path current = Path.of(applicationFileValue).toAbsolutePath().normalize();
        while (current != null && !"bootstrap".equals(current.getFileName() == null
                ? "" : current.getFileName().toString())) {
            current = current.getParent();
        }
        if (current == null) {
            return null;
        }
        Path bootJar = newestFile(current.resolve("target"), path -> path.getFileName().toString().endsWith(".jar"));
        if (bootJar == null) {
            return null;
        }
        try (JarFile jar = new JarFile(bootJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("BOOT-INF/lib/") && isPostgresDriverJar(Path.of(name).getFileName().toString())) {
                    extractedDriver = Files.createTempFile("ragent-postgresql-driver-", ".jar");
                    try (InputStream input = jar.getInputStream(entry)) {
                        Files.copy(input, extractedDriver, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    return extractedDriver;
                }
            }
        }
        return null;
    }

    private static Path newestFile(Path directory, Predicate<Path> filter) throws IOException {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (Stream<Path> paths = Files.walk(directory, 3)) {
            return paths.filter(Files::isRegularFile)
                    .filter(filter)
                    .max(Comparator.comparingLong(JdbcClient::lastModified))
                    .orElse(null);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static boolean isPostgresDriverJar(String filename) {
        return filename.startsWith("postgresql-") && filename.endsWith(".jar")
                && !filename.endsWith("-sources.jar") && !filename.endsWith("-javadoc.jar");
    }

    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < script.length(); index++) {
            char value = script.charAt(index);
            char next = index + 1 < script.length() ? script.charAt(index + 1) : '\0';
            if (lineComment) {
                current.append(value);
                if (value == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                current.append(value);
                if (value == '*' && next == '/') {
                    current.append(next);
                    index++;
                    blockComment = false;
                }
                continue;
            }
            if (!singleQuoted && !doubleQuoted && value == '-' && next == '-') {
                current.append(value).append(next);
                index++;
                lineComment = true;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && value == '/' && next == '*') {
                current.append(value).append(next);
                index++;
                blockComment = true;
                continue;
            }
            if (value == '\'' && !doubleQuoted) {
                current.append(value);
                if (singleQuoted && next == '\'') {
                    current.append(next);
                    index++;
                } else {
                    singleQuoted = !singleQuoted;
                }
                continue;
            }
            if (value == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                current.append(value);
                continue;
            }
            if (value == ';' && !singleQuoted && !doubleQuoted) {
                addStatement(statements, current);
                continue;
            }
            current.append(value);
        }
        addStatement(statements, current);
        return List.copyOf(statements);
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String value = current.toString().trim();
        current.setLength(0);
        if (!value.isEmpty()) {
            statements.add(value);
        }
    }

    private static String firstLine(String value) {
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        if (registeredDriver != null) {
            try {
                DriverManager.deregisterDriver(registeredDriver);
            } catch (SQLException ex) {
                failure = new IOException("卸载 PostgreSQL JDBC 驱动失败", ex);
            }
            registeredDriver = null;
        }
        if (driverClassLoader != null) {
            try {
                driverClassLoader.close();
            } catch (IOException ex) {
                failure = merge(failure, ex);
            }
            driverClassLoader = null;
        }
        if (extractedDriver != null) {
            try {
                Files.deleteIfExists(extractedDriver);
            } catch (IOException ex) {
                failure = merge(failure, ex);
            }
            extractedDriver = null;
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static IOException merge(IOException current, IOException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    private static final class DriverBridge implements Driver {
        private final Driver delegate;

        private DriverBridge(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}

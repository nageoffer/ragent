/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 回归台的运行期依赖：配置、HTTP、JDBC 与登录态
 * 与 InitializerContext 分开是因为回归不需要模板数据集，也不做任何完整性校验
 */
final class RegressionContext implements AutoCloseable {

    private static final Set<String> FLAG_ARGUMENTS = Set.of("verbose");

    private final Path suiteDir;
    private final InitializerConfig config;
    private final RagentHttpClient http;
    private final JdbcClient jdbc;
    private final AgentChatClient chat;
    private final AgentStateProbe probe;
    private final AgentMemoryProbe memory;
    private final Map<String, String> arguments;

    private String userId;

    private RegressionContext(Path suiteDir, InitializerConfig config, Map<String, String> arguments) {
        this.suiteDir = suiteDir;
        this.config = config;
        this.arguments = arguments;
        this.http = new RagentHttpClient(config);
        this.jdbc = new JdbcClient(config, "database");
        this.chat = new AgentChatClient(http, config);
        this.probe = new AgentStateProbe(jdbc);
        this.memory = new AgentMemoryProbe(jdbc);
    }

    static RegressionContext load(String[] args) throws IOException {
        Map<String, String> arguments = parseArguments(args);
        String suiteValue = arguments.get("suite-dir");
        if (suiteValue == null || suiteValue.isBlank()) {
            throw new IllegalArgumentException("缺少参数 --suite-dir <目录>");
        }
        Path suiteDir = Path.of(suiteValue).toAbsolutePath().normalize();
        String configValue = arguments.get("config");
        Path configFile = configValue == null || configValue.isBlank()
                ? suiteDir.resolve("regression.properties")
                : Path.of(configValue).toAbsolutePath().normalize();
        return new RegressionContext(suiteDir, InitializerConfig.load(configFile), arguments);
    }

    /**
     * 记忆按 userId 分片，回归台必须拿到真实 userId 才能定位 t_agent_state
     */
    String login() throws IOException, InterruptedException {
        RagentHttpClient.LoginSession session = http.login(config.require("auth.username"),
                config.require("auth.password"));
        if (session.userId() == null || session.userId().isBlank()) {
            throw new IllegalStateException("登录响应缺少 userId，无法定位会话状态");
        }
        userId = session.userId();
        return userId;
    }

    String userId() {
        if (userId == null) {
            throw new IllegalStateException("请求前必须先登录");
        }
        return userId;
    }

    Path suiteDir() {
        return suiteDir;
    }

    InitializerConfig config() {
        return config;
    }

    AgentChatClient chat() {
        return chat;
    }

    AgentStateProbe probe() {
        return probe;
    }

    AgentMemoryProbe memory() {
        return memory;
    }

    String argument(String name, String defaultValue) {
        String value = arguments.get(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    Duration turnTimeout() {
        return Duration.ofSeconds(config.getInt("turn.timeout-seconds", 900));
    }

    /**
     * 状态落库是流结束后的异步收尾，读不到就退避重读
     */
    AgentStateProbe.Snapshot probeWithRetry(String sessionId, String anchor) throws Exception {
        int attempts = Math.max(1, config.getInt("probe.max-attempts", 5));
        long interval = Math.max(0, config.getInt("probe.retry-interval-seconds", 2)) * 1000L;
        AgentStateProbe.Snapshot snapshot = probe.snapshot(userId(), sessionId, anchor);
        for (int attempt = 1; attempt < attempts && !snapshot.present(); attempt++) {
            Thread.sleep(interval);
            snapshot = probe.snapshot(userId(), sessionId, anchor);
        }
        return snapshot;
    }

    private static Map<String, String> parseArguments(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            String token = args[index];
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("无法识别的参数: " + token);
            }
            String body = token.substring(2);
            int equals = body.indexOf('=');
            if (equals > 0) {
                result.put(body.substring(0, equals), body.substring(equals + 1));
                continue;
            }
            if (FLAG_ARGUMENTS.contains(body)) {
                result.put(body, "true");
                continue;
            }
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException("参数 --" + body + " 缺少取值");
            }
            result.put(body, args[++index]);
        }
        return result;
    }

    @Override
    public void close() throws IOException {
        try {
            http.close();
        } finally {
            jdbc.close();
        }
    }

    /**
     * 与 MainSupport 同构的入口封装，回归台的失败前缀与初始化器分开
     */
    static void run(String[] args, RegressionAction action) {
        try (RegressionContext context = RegressionContext.load(args)) {
            action.execute(context);
        } catch (Exception ex) {
            System.err.println("[regression] FAILED: " + describe(ex));
            if (Boolean.parseBoolean(System.getenv().getOrDefault("RAGENT_REGRESSION_DEBUG", "false"))) {
                ex.printStackTrace(System.err);
            }
            System.exit(1);
        }
    }

    /**
     * 连接类异常常常没有 message，光印 null 会让人以为是回归台自己的空指针
     */
    private static String describe(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
                ? ex.getClass().getName() + "（服务或数据库未启动时通常是这个）"
                : message;
    }

    @FunctionalInterface
    interface RegressionAction {
        void execute(RegressionContext context) throws Exception;
    }
}

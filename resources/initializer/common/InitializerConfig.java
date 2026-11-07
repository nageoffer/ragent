/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** UTF-8 properties loader with ${ENV} and ${ENV:default} expansion. */
final class InitializerConfig {

    private final Path source;
    private final Properties values;

    private InitializerConfig(Path source, Properties values) {
        this.source = source;
        this.values = values;
    }

    static InitializerConfig load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("配置文件不存在: " + file.toAbsolutePath());
        }
        Properties raw = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            raw.load(reader);
        }
        Properties merged = new Properties();
        Path applicationFile = resolveSibling(file, raw.getProperty("application.config"));
        if (applicationFile != null) {
            merged.putAll(ApplicationYamlConfig.load(applicationFile));
            merged.setProperty("application.config-resolved", applicationFile.toString());
        }
        // 业务库只有比特严选那套数据集要用，它的身份以 mcp-server 的 yml 为准，没配就整段不存在
        Path mcpFile = resolveSibling(file, raw.getProperty("application.mcp-config"));
        if (mcpFile != null) {
            merged.putAll(ApplicationYamlConfig.loadBit(mcpFile));
        }
        merged.putAll(raw);
        Properties expanded = new Properties();
        for (String name : merged.stringPropertyNames()) {
            expanded.setProperty(name, expandPlaceholders(merged.getProperty(name)));
        }
        return new InitializerConfig(file.toAbsolutePath().normalize(), expanded);
    }

    static Properties loadProperties(Path file) throws IOException {
        return load(file).values;
    }

    /**
     * 把配置里写的相对路径按配置文件自身所在目录解析，没配返回 null
     */
    private static Path resolveSibling(Path configFile, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Path path = Path.of(expandPlaceholders(value.trim()));
        if (path.isAbsolute()) {
            return path;
        }
        return configFile.toAbsolutePath().normalize().getParent().resolve(path).normalize();
    }

    String require(String key) {
        String value = get(key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少配置项 " + key + "，文件: " + source);
        }
        return value;
    }

    String get(String key, String defaultValue) {
        String value = values.getProperty(key);
        return value == null ? defaultValue : value.trim();
    }

    int requireInt(String key) {
        require(key);
        return getInt(key, 0);
    }

    int getInt(String key, int defaultValue) {
        String value = get(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("配置项不是整数: " + key + "=" + value, ex);
        }
    }

    boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key, null);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value);
    }

    List<String> getList(String key) {
        String value = get(key, "");
        if (value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String item : value.split(",")) {
            if (!item.isBlank()) {
                result.add(item.trim());
            }
        }
        return List.copyOf(result);
    }

    static String expandPlaceholders(String input) {
        StringBuilder output = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); ) {
            int start = input.indexOf("${", index);
            if (start < 0) {
                output.append(input, index, input.length());
                break;
            }
            output.append(input, index, start);
            int end = input.indexOf('}', start + 2);
            if (end < 0) {
                throw new IllegalArgumentException("环境变量占位符未闭合: " + input);
            }
            String expression = input.substring(start + 2, end);
            int separator = expression.indexOf(':');
            String name = separator < 0 ? expression : expression.substring(0, separator);
            String defaultValue = separator < 0 ? null : expression.substring(separator + 1);
            String value = System.getenv(name);
            if (value == null) {
                if (defaultValue == null) {
                    throw new IllegalArgumentException("缺少环境变量: " + name);
                }
                value = defaultValue;
            }
            output.append(value);
            index = end + 1;
        }
        return output.toString();
    }
}

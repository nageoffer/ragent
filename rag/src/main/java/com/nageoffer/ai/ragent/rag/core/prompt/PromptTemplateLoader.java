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

package com.nageoffer.ai.ragent.rag.core.prompt;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示模板加载器
 * 负责从类路径下加载提示模板文件，并支持模板变量填充功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateLoader {

    private final ResourceLoader resourceLoader;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> sectionCache = new ConcurrentHashMap<>();

    /**
     * 加载指定路径的提示模板
     *
     * @param path 模板文件路径，支持classpath:前缀
     * @return 模板内容字符串
     * @throws IllegalArgumentException 当路径为空时抛出
     * @throws IllegalStateException    当模板文件不存在或读取失败时抛出
     */
    public String load(String path) {
        if (StrUtil.isBlank(path)) {
            throw new IllegalArgumentException("提示模板路径为空");
        }
        return cache.computeIfAbsent(path, this::readResource);
    }

    /**
     * 渲染提示模板，将模板中的占位符替换为实际值
     *
     * @param path  模板文件路径
     * @param slots 占位符映射表，键为占位符名称，值为替换内容
     * @return 渲染后的完整提示文本
     */
    public String render(String path, Map<String, String> slots) {
        String template = load(path);
        String filled = PromptTemplateUtils.fillSlots(template, slots);
        return PromptTemplateUtils.cleanupPrompt(filled);
    }

    /**
     * 加载模板文件中指定 section 的原始内容
     *
     * @param path    模板文件路径
     * @param section section 名称（对应 {@code --- section: name ---} 中的 name）
     * @return section 的原始模板内容
     * @throws IllegalStateException 当 section 不存在时抛出
     */
    public String loadSection(String path, String section) {
        Map<String, String> sections = sectionCache.computeIfAbsent(path, p -> {
            String content = load(p);
            return PromptTemplateUtils.parseSections(content);
        });
        String template = sections.get(section);
        if (template == null) {
            throw new IllegalStateException("模板 section 不存在：" + path + " -> " + section);
        }
        return template;
    }

    /**
     * 渲染模板文件中指定 section，并填充占位符
     *
     * @param path    模板文件路径
     * @param section section 名称
     * @param slots   占位符映射表
     * @return 渲染后的文本
     */
    public String renderSection(String path, String section, Map<String, String> slots) {
        String template = loadSection(path, section);
        String filled = PromptTemplateUtils.fillSlots(template, slots);
        return PromptTemplateUtils.cleanupPrompt(filled);
    }

    /**
     * 从资源路径读取模板内容
     *
     * @param path 模板文件路径
     * @return 模板内容字符串
     * @throws IllegalStateException 当模板文件不存在或读取失败时抛出
     */
    private String readResource(String path) {
        String location = path.startsWith("classpath:") ? path : "classpath:" + path;
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("提示词模板路径不存在：" + path);
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取提示模板失败，路径：{}", path, e);
            throw new IllegalStateException("读取提示模板失败，路径：" + path, e);
        }
    }
}

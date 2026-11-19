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

package com.nageoffer.ai.ragent.rag.core.intent;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.IntentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交付的 application.yaml 必须真的能绑到新属性上
 * <p>
 * 只验绑定形状，不起容器：字段名拼错时单测比启动失败更早发现
 */
class JevConfigBindingTest {

    @Test
    @DisplayName("ai.jev 与 rag.intent 按交付值绑定，zenmux 的 jev 端点是 System One 路径")
    void bindsShippedConfiguration() throws IOException {
        Binder binder = binder();

        // 兼容原部署的保证是「不写这个键就走 llm」，钉在类默认值上；
        // yaml 里的当前值会被实验临时改成 jev，所以只要求它是合法策略
        assertEquals("llm", new IntentProperties().getType(), "不配置 rag.intent.type 时必须走 llm");

        IntentProperties intent = binder.bind("rag.intent", IntentProperties.class).get();
        assertEquals(0.4D, intent.getJev().getMinNoul(), 1e-9);
        assertDoesNotThrow(intent::afterPropertiesSet, "yaml 里的 type 必须是 llm / jev 之一");

        // 按窄前缀分别绑定：整棵 ai 树里有 ${rag.default.dimension} 这类占位符，裸 Binder 解析不了
        AIModelProperties.Jev jev = binder.bind("ai.jev", AIModelProperties.Jev.class).get();
        assertEquals("jev-stable", jev.getDefaultModel());
        AIModelProperties.ModelCandidate candidate = jev.getCandidates().stream()
                .filter(each -> "jev-stable".equals(each.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("zenmux", candidate.getProvider());
        assertEquals("typesafe/jev-1.14", candidate.getModel(), "版本固定，不用浮动标识");

        AIModelProperties.ProviderConfig zenmux = binder
                .bind("ai.providers.zenmux", AIModelProperties.ProviderConfig.class).get();
        assertEquals("https://zenmux.ai", zenmux.getUrl());
        assertEquals("/api/v1/systemone", zenmux.getEndpoints().get("jev"),
                "Jev 不走 chat/completions，也不是 TypeSafe 原站路径");
        assertEquals("/api/v1/chat/completions", zenmux.getEndpoints().get("chat"),
                "聊天端点不受本次接入影响");
    }

    private Binder binder() throws IOException {
        Path yaml = Path.of("..", "bootstrap", "src", "main", "resources", "application.yaml");
        assertTrue(Files.exists(yaml), "找不到 application.yaml: " + yaml.toAbsolutePath());
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new FileSystemResource(yaml.toFile()));
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        return new Binder(ConfigurationPropertySources.from(propertySources));
    }
}

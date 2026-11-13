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
import com.nageoffer.ai.ragent.infra.jev.JevClient;
import com.nageoffer.ai.ragent.infra.jev.JevTarget;
import com.nageoffer.ai.ragent.rag.config.IntentProperties;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 策略切换的装配契约
 * <p>
 * 切到 jev 不能关掉 {@link DefaultIntentClassifier}：它同时是意图树注册表，
 * 关掉会让 MCP 工具发现和歧义检测一起拿不到拓扑
 */
class IntentClassifierWiringTest {

    private final DefaultIntentClassifier defaultIntentClassifier = mock(DefaultIntentClassifier.class);

    @Test
    @DisplayName("默认 llm：唯一的分类器是原实现，Jev 那个 bean 根本不创建")
    void llmKeepsOnlyDefaultClassifier() {
        runner("llm").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IntentClassifier.class)).isSameAs(defaultIntentClassifier);
            assertThat(context).doesNotHaveBean(JevIntentClassifier.class);
        });
    }

    @Test
    @DisplayName("jev：分类器换成 Jev 实现，注册表仍是原实现")
    void jevOverridesClassifierButKeepsRegistry() {
        runner("jev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IntentClassifier.class)).isInstanceOf(JevIntentClassifier.class);
            assertThat(context.getBean(IntentNodeRegistry.class)).isSameAs(defaultIntentClassifier);
        });
    }

    @Test
    @DisplayName("配置缺失时分类器建不起来，不会带着半截配置进运行期")
    void jevBeanFailsWhenConfigIncomplete() {
        new ApplicationContextRunner()
                .withBean(DefaultIntentClassifier.class, () -> defaultIntentClassifier)
                .withBean(JevClient.class, () -> new JevClient(new AIModelProperties(), new OkHttpClient()))
                .withBean(IntentProperties.class, () -> properties("jev"))
                .withUserConfiguration(JevScan.class)
                .withPropertyValues("rag.intent.type=jev")
                .run(context -> {
                    // 正常部署里配置缺失由启动期的 JevConfigValidator 先拦下，这里兜的是它之后的底
                    assertThatThrownBy(() -> context.getBean(IntentClassifier.class))
                            .rootCause().isInstanceOf(IllegalStateException.class);
                });
    }

    private ApplicationContextRunner runner(String type) {
        JevClient jevClient = mock(JevClient.class);
        when(jevClient.requireTarget()).thenReturn(target());
        return new ApplicationContextRunner()
                .withBean(DefaultIntentClassifier.class, () -> defaultIntentClassifier)
                .withBean(JevClient.class, () -> jevClient)
                .withBean(IntentProperties.class, () -> properties(type))
                .withUserConfiguration(JevScan.class)
                .withPropertyValues("rag.intent.type=" + type);
    }

    private static IntentProperties properties(String type) {
        IntentProperties properties = new IntentProperties();
        properties.setType(type);
        return properties;
    }

    private static JevTarget target() {
        return new JevTarget("http://127.0.0.1/api/v1/systemone", "test-key", "typesafe/jev-1.14");
    }

    /**
     * 扫生产类本身，验的就是它自己挂的 {@code @Primary} 与 {@code @ConditionalOnProperty}，不复刻注解
     */
    @Configuration
    @ComponentScan(basePackageClasses = JevIntentClassifier.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JevIntentClassifier.class))
    static class JevScan {
    }
}

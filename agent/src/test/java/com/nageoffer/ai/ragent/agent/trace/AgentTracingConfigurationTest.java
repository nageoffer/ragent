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

package com.nageoffer.ai.ragent.agent.trace;

import com.nageoffer.ai.ragent.rag.config.InputGuardProperties;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadableSpan;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务身份走 resource 不走 span 属性：它描述的是「谁产出的这条链路」，逐 span 重复一遍纯属浪费
 */
class AgentTracingConfigurationTest {

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    private static final AttributeKey<String> SERVICE_VERSION = AttributeKey.stringKey("service.version");
    private static final AttributeKey<String> ENVIRONMENT = AttributeKey.stringKey("deployment.environment.name");
    private static final AttributeKey<String> INSTANCE_ID = AttributeKey.stringKey("service.instance.id");

    @Test
    void shouldWriteFullIdentityWhenVersionAndEnvironmentConfigured() {
        AgentTraceProperties properties = new AgentTraceProperties();
        properties.setServiceVersion("1.4.2");
        properties.setEnvironment("staging");

        Resource resource = AgentTracingConfiguration.resource(
                properties, buildInfo("0.0.1-SNAPSHOT"), "ragent-service");

        assertThat(resource.getAttribute(SERVICE_NAME)).isEqualTo("ragent-service");
        // 显式配置压过构建号：部署方要用发布单号盖掉 pom 版本时得有地方写
        assertThat(resource.getAttribute(SERVICE_VERSION)).isEqualTo("1.4.2");
        assertThat(resource.getAttribute(ENVIRONMENT)).isEqualTo("staging");
    }

    /**
     * 没配版本时退回构建期写进包里的那个：「这条链路是哪一版产出的」不该靠部署方每次手填
     */
    @Test
    void shouldFallBackToBuildVersionWhenNotConfigured() {
        AgentTraceProperties properties = new AgentTraceProperties();

        Resource resource = AgentTracingConfiguration.resource(
                properties, buildInfo("0.0.1-SNAPSHOT"), "ragent-service");

        assertThat(resource.getAttribute(SERVICE_VERSION)).isEqualTo("0.0.1-SNAPSHOT");
    }

    /**
     * 没配也没有构建信息就整项不写：空串是个能被后端当真值收下的取值，会把所有没配版本的实例聚成「版本 = 空」的一族
     */
    @Test
    void shouldOmitVersionAndEnvironmentWhenBlank() {
        AgentTraceProperties properties = new AgentTraceProperties();
        properties.setServiceVersion("");
        properties.setEnvironment("   ");

        Resource resource = AgentTracingConfiguration.resource(properties, null, "ragent-service");

        assertThat(resource.getAttribute(SERVICE_NAME)).isEqualTo("ragent-service");
        assertThat(resource.getAttribute(SERVICE_VERSION)).isNull();
        assertThat(resource.getAttribute(ENVIRONMENT)).isNull();
    }

    /**
     * 实例标识显式配置优先：K8s 之外没有 HOSTNAME 可借时，部署方得能自己指名
     */
    @Test
    void shouldWriteConfiguredInstanceId() {
        AgentTraceProperties properties = new AgentTraceProperties();
        properties.setInstanceId("ragent-pod-7");

        Resource resource = AgentTracingConfiguration.resource(properties, null, "ragent-service");

        assertThat(resource.getAttribute(INSTANCE_ID)).isEqualTo("ragent-pod-7");
    }

    /**
     * 没配也照写，且同进程两次取到同一个：这一项空着就没有实例维度，多实例的链路混作一族
     * 每次换值比空着更糟——同一台会被当成一群，按实例过滤反倒把人带偏
     */
    @Test
    void shouldAlwaysCarryStableInstanceIdWhenNotConfigured() {
        AgentTraceProperties properties = new AgentTraceProperties();

        String first = AgentTracingConfiguration.resource(properties, null, "ragent-service")
                .getAttribute(INSTANCE_ID);
        String second = AgentTracingConfiguration.resource(properties, null, "ragent-service")
                .getAttribute(INSTANCE_ID);

        assertThat(first).isNotBlank().isEqualTo(second);
    }

    private static BuildProperties buildInfo(String version) {
        Properties entries = new Properties();
        entries.setProperty("version", version);
        return new BuildProperties(entries);
    }

    /**
     * 容器里走完整条链：打包写下的 build-info → Boot 装出 BuildProperties → SDK resource 的 service.version
     * 只测静态方法证明不了 ObjectProvider 有没有取到 bean，这一条堵的是「构建写了、运行没读」那种漂移
     */
    @Test
    void shouldReadBuildVersionIntoSdkResourceInsideContainer() throws IOException {
        Path buildInfo = Files.createTempDirectory("ragent-build-info").resolve("build-info.properties");
        Files.writeString(buildInfo, "build.version=9.9.9-audit\n");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ProjectInfoAutoConfiguration.class))
                .withUserConfiguration(AgentTraceProperties.class, InputGuardProperties.class, AgentTracingConfiguration.class)
                .withPropertyValues(
                        "ragent.engine.type=agent",
                        "agent.trace.enabled=true",
                        "agent.trace.public-key=pk-test",
                        "agent.trace.secret-key=sk-test",
                        "spring.application.name=ragent-test-service",
                        "spring.info.build.location=file:" + buildInfo.toAbsolutePath())
                .run(context -> {
                    assertThat(context).hasSingleBean(BuildProperties.class);
                    OpenTelemetrySdk sdk = context.getBean(OpenTelemetrySdk.class);
                    Resource resource = sdk.getSdkTracerProvider().get("probe").spanBuilder("probe")
                            .startSpan() instanceof ReadableSpan span ? span.toSpanData().getResource() : null;
                    assertThat(resource).isNotNull();
                    assertThat(resource.getAttribute(SERVICE_NAME)).isEqualTo("ragent-test-service");
                    assertThat(resource.getAttribute(SERVICE_VERSION)).isEqualTo("9.9.9-audit");
                });
        // 全局位置由容器关闭时的 close 释放不掉，留给下一条用例会撞上「已被占用」
        GlobalOpenTelemetry.resetForTest();
    }

    /**
     * 字段缺省值与基础配置都明确默认采集内容：只打开 tracing 就应得到可读的 Input / Output，
     * 需要纯骨架或有额外合规要求的部署再显式关闭内容采集
     */
    @Test
    void shouldKeepContentCaptureEnabledInBaseConfiguration() {
        AgentTraceProperties bound = bindBaseConfiguration();

        assertThat(bound.isCaptureContent()).isTrue();
        assertThat(bound.isEnabled()).isFalse();
    }

    /**
     * 不经过 yaml 绑定时也要遵守同一默认契约，避免不同启动路径得到一套有内容、一套空节点的 trace
     */
    @Test
    void shouldEnableContentCaptureInPropertyDefaults() {
        assertThat(new AgentTraceProperties().isCaptureContent()).isTrue();
    }

    /**
     * 按 Boot 自己那套加载与绑定，读的是与运行期同一个文件，抄一份到测试资源里就测不到真配置了
     */
    private AgentTraceProperties bindBaseConfiguration() {
        Path yaml = Path.of("..", "bootstrap", "src", "main", "resources", "application.yaml");
        assertThat(yaml).as("基础配置文件已挪位置, 这条用例要跟着改").exists();
        List<PropertySource<?>> sources;
        try {
            sources = new YamlPropertySourceLoader().load("application", new FileSystemResource(yaml));
        } catch (IOException e) {
            throw new IllegalStateException("读不到基础配置", e);
        }
        MutablePropertySources holder = new MutablePropertySources();
        sources.forEach(holder::addLast);
        return new Binder(ConfigurationPropertySources.from(holder))
                .bind("agent.trace", AgentTraceProperties.class)
                .orElseThrow(() -> new AssertionError("基础配置里没有 agent.trace 这一段"));
    }
}

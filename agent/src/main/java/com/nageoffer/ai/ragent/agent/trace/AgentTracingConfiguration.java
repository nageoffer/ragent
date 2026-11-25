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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.rag.config.InputGuardProperties;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.resources.ResourceBuilder;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * 追踪装配：OTLP 导出器 + 官方 span 骨架 + 我们的内容装饰
 * 只在 agent.trace.enabled=true 时进来，关掉时连 Reactor 全局钩子都不装
 */
@Slf4j
@Configuration
@ConditionalOnAgentEngine
@ConditionalOnProperty(prefix = "agent.trace", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class AgentTracingConfiguration {

    /**
     * 缺这个头，直传的 OTel 数据在 LangFuse 里最多延迟十分钟才可见
     */
    private static final String INGESTION_VERSION_HEADER = "x-langfuse-ingestion-version";

    /**
     * 三处建 span 的地方共用这一个 scope，工具体那处建在中间件链之外也认它
     * 各写各的会让同一次调用的节点在后端分属两族，按 scope 过滤时一条链路只剩半棵
     */
    static final String INSTRUMENTATION_SCOPE = "com.nageoffer.ai.ragent.agent";

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

    /**
     * 语义约定 1.27 起环境键从 deployment.environment 改成带 name 后缀的这个
     */
    private static final AttributeKey<String> SERVICE_VERSION = AttributeKey.stringKey("service.version");
    private static final AttributeKey<String> DEPLOYMENT_ENVIRONMENT =
            AttributeKey.stringKey("deployment.environment.name");
    private static final AttributeKey<String> SERVICE_INSTANCE_ID = AttributeKey.stringKey("service.instance.id");

    /**
     * 兜底实例标识：进程内固定、重启即变，它标识的是这个进程而非这台机器
     * 随机串换不来运维含义，只保证两个实例不会撞成一个，配 instance-id 或注入 HOSTNAME 才有得查
     */
    private static final String FALLBACK_INSTANCE_ID = UUID.randomUUID().toString();

    private final AgentTraceProperties traceProperties;

    /**
     * 构建期由 spring-boot-maven-plugin 写进包里的版本，没走 Maven 打包（IDE 直接起）时 bean 缺席
     */
    private final ObjectProvider<BuildProperties> buildProperties;

    /**
     * 声明成 OpenTelemetrySdk 而非 OpenTelemetry：关机时要 close 落最后一批，也给别处的
     * ConditionalOnMissingBean(OpenTelemetry) 留出退让空间
     */
    @Bean(destroyMethod = "close")
    public OpenTelemetrySdk agentOpenTelemetrySdk(
            @Value("${spring.application.name}") String applicationName) {
        if (StrUtil.isBlank(traceProperties.getEndpoint())
                || StrUtil.isBlank(traceProperties.getPublicKey())
                || StrUtil.isBlank(traceProperties.getSecretKey())) {
            throw new IllegalStateException("agent.trace 已开启但 endpoint / public-key / secret-key 未配置齐全");
        }
        // 抢不到全局位置与缺配置同属一类失败：追踪都给不出来，都在显式开启之后，故一样按启动失败处理
        // isSet 为真有两种来源，另一套 OTel 装配，或更早的 GlobalOpenTelemetry.get() 把它就地设成了 no-op
        if (GlobalOpenTelemetry.isSet()) {
            throw new IllegalStateException("GlobalOpenTelemetry 已被占用, 官方 span 不会进这套 SDK, "
                    + "LangFuse 只会收到孤儿轮次或干脆收不到, 关掉 agent.trace.enabled 或排掉另一套 OTel 装配");
        }

        // LangFuse 只收 HTTP 的 OTLP，没有 gRPC 端点
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(traceProperties.getEndpoint())
                .addHeader("Authorization", basicCredential())
                .addHeader(INGESTION_VERSION_HEADER, "4")
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource(traceProperties, buildProperties.getIfAvailable(), applicationName))
                // 显式声明，别让「有没有采样」取决于 SDK 默认值：一个 Agent 会话的 span 数以十计而非以万计，
                // 抽样省下的那点量换来的是「这条链路正好没被采到」，排查场景下这笔账反着算
                // TODO 上了多租共享的追踪后端再谈按 trace 抽样，届时要在根 span 上做决定，别把一棵树采成半棵
                .setSampler(Sampler.alwaysOn())
                .setSpanLimits(hardLimits())
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        // 官方中间件只认全局实例，取 tracer 是在调用时刻，所以这一步必须先于它构造
        GlobalOpenTelemetry.set(sdk);
        log.info("Agent 链路追踪已启用, 上报至 {}", traceProperties.getEndpoint());
        return sdk;
    }

    /**
     * span 骨架、耗时、token 与 Reactor 上下文透传仍走官方实现，只有 acting 那一层换成我方的批节点
     * 形参上的 sdk 是为了保证全局注册先于它构造：父类继承来的钩子取 tracer 是在调用时刻，认的是全局实例
     */
    @Bean
    public OtelTracingMiddleware otelTracingMiddleware(OpenTelemetrySdk sdk) {
        return new RagentOtelTracingMiddleware(sdk.getTracer(INSTRUMENTATION_SCOPE));
    }

    /**
     * 工具执行体的 span 建在中间件链之外，取不到注入进来的那份，故在这里把同一个实例推过去
     * 两处必须同源：字段上限与内容开关各配一套的话，同一次调用在批与执行体上会读出两种口径
     */
    @Bean
    public AgentTraceEnrichmentMiddleware agentTraceEnrichmentMiddleware(OpenTelemetrySdk sdk,
                                                                         InputGuardProperties inputGuardProperties) {
        AgentTraceSerializer serializer = new AgentTraceSerializer(traceProperties.getMaxFieldChars(),
                traceProperties.getMaxAttributeChars(),
                traceProperties.isCaptureContent());
        AgentToolBodyTracer.configure(serializer);
        return new AgentTraceEnrichmentMiddleware(sdk.getTracer(INSTRUMENTATION_SCOPE), serializer,
                inputGuardProperties.getType());
    }

    /**
     * 静态位置得随上下文一起退场，留着上一份配置会让下一次装配前的调用按已经作废的开关采内容
     */
    @PreDestroy
    public void releaseToolBodySerializer() {
        AgentToolBodyTracer.configure(null);
    }

    /**
     * 服务身份：名字直接沿用 spring.application.name，版本显式配置优先、其次取构建信息，环境留空就不写
     * 空串比缺失更糟，后端会当成一个真实取值，把没配版本的实例聚成「版本 = 空」的一族
     * 版本必须有一个可靠来源：它是「这条链路由哪一版代码产出」唯一的凭据，缺了就只能说契约相符
     * 实例标识与上面三项相反，永远写：留空就没有「这条链路哪台产出」这个维度，多实例下两台混作一族
     */
    static Resource resource(AgentTraceProperties properties, BuildProperties build, String applicationName) {
        ResourceBuilder builder = Resource.getDefault().toBuilder()
                .put(SERVICE_NAME, applicationName);
        String version = StrUtil.blankToDefault(properties.getServiceVersion(),
                build == null ? null : build.getVersion());
        if (StrUtil.isNotBlank(version)) {
            builder.put(SERVICE_VERSION, version);
        }
        if (StrUtil.isNotBlank(properties.getEnvironment())) {
            builder.put(DEPLOYMENT_ENVIRONMENT, properties.getEnvironment());
        }
        builder.put(SERVICE_INSTANCE_ID, resolveInstanceId(properties));
        return builder.build();
    }

    /**
     * 显式配置优先，其次容器注入的 HOSTNAME，K8s 下它就是 Pod 名，随重建而变正合实例语义
     */
    private static String resolveInstanceId(AgentTraceProperties properties) {
        String resolved = StrUtil.blankToDefault(properties.getInstanceId(), System.getenv("HOSTNAME"));
        return StrUtil.isNotBlank(resolved) ? resolved : FALLBACK_INSTANCE_ID;
    }

    /**
     * SDK 侧的硬兜底，只防我们漏算的那类属性：正常内容早在序列化时就被应用预算收干净了
     * 必须留出高一档的余量，与应用预算取同值会让 SDK 在我们补好收尾的 JSON 上再切一刀，切出半截串
     */
    private SpanLimits hardLimits() {
        int hardLimit = (int) Math.min((long) traceProperties.getMaxAttributeChars() * 2, Integer.MAX_VALUE);
        return SpanLimits.builder()
                .setMaxAttributeValueLength(hardLimit)
                .build();
    }

    private String basicCredential() {
        String raw = traceProperties.getPublicKey() + ":" + traceProperties.getSecretKey();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}

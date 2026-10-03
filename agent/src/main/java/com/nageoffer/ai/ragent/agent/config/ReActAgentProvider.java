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

package com.nageoffer.ai.ragent.agent.config;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.confirm.AgentConfirmDenialMiddleware;
import com.nageoffer.ai.ragent.agent.memory.AgentContextCompactionMiddleware;
import com.nageoffer.ai.ragent.agent.memory.AgentUserMemoryMiddleware;
import com.nageoffer.ai.ragent.agent.skill.AgentSkillMaskingMiddleware;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.agent.tool.AgentToolBatchMiddleware;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.ResolvedCatalog;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceEnrichmentMiddleware;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 主 Agent 供给器：单例复用，AGENT_MAIN 人设或工具目录变化时懒重建
 * 会话状态在 PgAgentStateStore 中按次加载，重建不丢历史
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class ReActAgentProvider {

    private static final String AGENT_NAME = "RagentAI";
    private volatile CachedAgent cached;

    private final AgentPromptResolver agentPromptResolver;
    private final AgentToolCatalog toolCatalog;
    private final OpenAIChatModel agentChatModel;
    private final PgAgentStateStore agentStateStore;
    private final AgentProperties agentProperties;
    private final AgentUserMemoryMiddleware userMemoryMiddleware;
    private final AgentContextCompactionMiddleware contextCompactionMiddleware;
    private final AgentConfirmDenialMiddleware confirmDenialMiddleware;
    private final AgentSkillMaskingMiddleware skillMaskingMiddleware;
    private final AgentToolBatchMiddleware toolBatchMiddleware;
    private final ObjectProvider<OtelTracingMiddleware> otelTracingMiddleware;
    private final ObjectProvider<AgentTraceEnrichmentMiddleware> traceEnrichmentMiddleware;

    /**
     * 以人设内容和工具目录签名判断重建时机：控制台修改后无需重启，下一次会话生效
     */
    public ActiveAgent getAgent() {
        Map<String, String> prompts = agentPromptResolver.resolveAll();
        String persona = resolvePersona(prompts);
        ResolvedCatalog catalog = toolCatalog.resolve(prompts);
        CachedAgent current = cached;
        if (matches(current, persona, catalog)) {
            return current.activeAgent();
        }
        synchronized (this) {
            current = cached;
            if (matches(current, persona, catalog)) {
                return current.activeAgent();
            }

            // 旧实例不主动 close：在途会话仍在其上流式输出，交由 GC 回收
            ReActAgent agent = buildAgent(persona, catalog);
            ActiveAgent activeAgent = new ActiveAgent(agent, catalog);

            cached = new CachedAgent(persona, activeAgent);
            return activeAgent;
        }
    }

    private boolean matches(CachedAgent current, String persona, ResolvedCatalog catalog) {
        return current != null
                && current.persona().equals(persona)
                && current.activeAgent().catalog().fingerprint().equals(catalog.fingerprint());
    }

    private ReActAgent buildAgent(String persona, ResolvedCatalog catalog) {
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(AGENT_NAME)
                .sysPrompt(persona)
                .model(agentChatModel)
                .toolkit(toolCatalog.buildToolkit(catalog))
                .maxIters(agentProperties.getMaxIters())
                .maxRetries(agentProperties.getMaxRetries())
                .stateStore(agentStateStore);
        // 追踪包在最外层：span 要盖住记忆与压缩才量得到耗时
        otelTracingMiddleware.ifAvailable(builder::middleware);
        traceEnrichmentMiddleware.ifAvailable(builder::middleware);
        return builder
                // 外层先执行：记忆块插在人设与会话之间，压缩的 offset 比对自然吸收它
                .middleware(userMemoryMiddleware)
                .middleware(contextCompactionMiddleware)
                // 压缩之后：被压进摘要的拒绝结果已不在列表里，改写自然跳过
                .middleware(confirmDenialMiddleware)
                // 手册被压缩带走后遮蔽跟着复位，工具与手册同进同出
                .middleware(skillMaskingMiddleware)
                // 最内层：批起止贴着真实执行，不随追踪开关变形
                .middleware(toolBatchMiddleware)
                .build();
    }

    private String resolvePersona(Map<String, String> prompts) {
        String persona = prompts.get(AgentPromptSlot.AGENT_MAIN.name());
        if (StrUtil.isBlank(persona)) {
            throw new IllegalStateException("Agent人设内容不允许为空");
        }
        return persona;
    }

    /**
     * 本轮取到的实例与它构建时用的目录快照，成对交出免得调用方各自再取一遍
     */
    public record ActiveAgent(ReActAgent agent, ResolvedCatalog catalog) {
    }

    /**
     * Provider 内部的缓存条目：persona 用于判断缓存是否过期，ActiveAgent 是对外使用的快照
     */
    private record CachedAgent(String persona, ActiveAgent activeAgent) {
    }
}

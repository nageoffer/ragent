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

package com.nageoffer.ai.ragent.agent.cart;

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunLifecycle;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
import com.nageoffer.ai.ragent.agent.tool.AgentToolContributor;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceContextKeys;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Set;

/** 购物车工具注册与运行状态均归业务模块管理。 */
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class CartAgentIntegration implements AgentToolContributor, AgentRunLifecycle {
    private final CartOperationService operations;
    private final CartOperationStore store;

    @Override
    public Set<String> reservedToolNames() {
        return Set.of(CartVerificationTool.NAME, CartOperationService.RPC_WRITE_TOOL, CartOperationService.QUERY_TOOL);
    }

    @Override
    public AgentTool createTool(McpToolBinding binding) {
        return CartOperationService.WRITE_TOOL.equals(binding.toolId()) ? new CartMcpToolProxy(binding, operations) : null;
    }

    @Override
    public List<AgentTool> additionalTools(List<McpToolBinding> bindings) {
        return bindings.stream().anyMatch(binding -> CartOperationService.WRITE_TOOL.equals(binding.toolId()))
                ? List.of(new CartVerificationTool(operations)) : List.of();
    }

    @Override
    public void onStart(RuntimeContext context, List<ConfirmResult> confirmations) {
        if (confirmations != null && confirmations.stream().anyMatch(result -> result.isConfirmed()
                && CartOperationService.WRITE_TOOL.equals(result.getToolCall().getName())
                && "add".equals(result.getToolCall().getInput().get("action")))) {
            context.put(CartRunControl.class, new CartRunControl(store,
                    context.get(AgentTraceContextKeys.TASK_ID), confirmations));
        }
    }

    @Override
    public void onInterrupt(RuntimeContext context) {
        CartRunControl control = context.get(CartRunControl.class);
        if (control != null) control.markCancelled();
    }

    @Override
    public void onDisconnect(RuntimeContext context) {
        CartRunControl control = context.get(CartRunControl.class);
        if (control != null) control.markDisconnected();
    }

    @Override
    public void onFinish(RuntimeContext context) {
        CartRunControl control = context.get(CartRunControl.class);
        if (control != null && control.cancelled()) control.cancel();
    }
}

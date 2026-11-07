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

import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
import com.nageoffer.ai.ragent.agent.tool.McpToolProxy;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import reactor.core.publisher.Mono;

/** 加购由协调服务执行，减少和移除仍走远程工具。 */
final class CartMcpToolProxy extends McpToolProxy {
    private final CartOperationService operations;
    private final McpClientWrapper client;

    CartMcpToolProxy(McpToolBinding binding, CartOperationService operations) {
        super(binding, binding.description()
                + " 加购结果不确定或用户要求核实、重试刚才那次时，使用 verify_cart_addition，只读核实原操作。"
                + "不要根据 query_cart 当前数量判断，也不要创建新的 add 调用代替续办；引导用户使用继续本次加购按钮。");
        this.operations = operations;
        this.client = binding.remote().client();
    }

    @Override
    protected Mono<ToolResultBlock> execute(ToolCallParam param) {
        return "add".equals(param.getInput().get("action"))
                ? operations.call(param, client) : super.execute(param);
    }
}

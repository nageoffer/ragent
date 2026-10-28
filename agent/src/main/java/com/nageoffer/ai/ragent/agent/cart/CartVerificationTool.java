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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.Map;

/** 只读续办入口：模型不能通过核实触发新的写操作。 */
@RequiredArgsConstructor
public class CartVerificationTool implements AgentTool {
    public static final String NAME = "verify_cart_addition";
    private final CartOperationService service;
    public String getName() { return NAME; }
    public String getDescription() {
        return "用户问刚才加购成功了吗、核实或重试刚才那次时，使用此工具核实原操作。"
                + "此工具只读；需要继续原操作时，引导用户点击操作条目的继续本次加购。"
                + "不要调用 update_cart_item 创建新操作，也不能按 query_cart 当前数量判断原操作成功。";
    }
    public boolean isReadOnly() { return true; }
    public Map<String, Object> getParameters() {
        return Map.of("type", "object", "properties", Map.of("skuCode", Map.of("type", "string", "description", "可选的商品 SKU")),
                "additionalProperties", false);
    }
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.defer(() -> {
            var ctx = param.getRuntimeContext();
            if (ctx == null || ctx.getUserId() == null || ctx.getSessionId() == null)
                return Mono.just(ToolResultBlock.error("缺少会话身份，不能核实"));
            Object sku = param.getInput().get("skuCode");
            return service.verifyForConversation(ctx.getUserId(), ctx.getSessionId(), sku instanceof String s ? s : null);
        }).subscribeOn(Schedulers.boundedElastic())
                .onErrorReturn(ToolResultBlock.error("本轮未能核实加购，结果仍未知，请使用加购结果条目的核实入口"));
    }
}

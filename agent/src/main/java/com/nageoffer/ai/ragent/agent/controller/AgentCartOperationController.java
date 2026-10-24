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

package com.nageoffer.ai.ragent.agent.controller;

import com.nageoffer.ai.ragent.agent.cart.CartOperation;
import com.nageoffer.ai.ragent.agent.cart.CartOperationActionResult;
import com.nageoffer.ai.ragent.agent.cart.CartOperationService;
import com.nageoffer.ai.ragent.agent.cart.CartOperationStore;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

@RestController
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentCartOperationController {
    private final CartOperationService service;
    private final CartOperationStore store;
    private final AgentRunGate runGate;

    @GetMapping("/agent/v1/conversations/{conversationId}/cart-operations")
    public Result<List<CartOperation>> list(@PathVariable String conversationId) {
        return Results.success(service.list(UserContext.getUserId(), conversationId));
    }

    @PostMapping("/agent/v1/cart-operations/{operationId}/verify")
    public Mono<Result<CartOperationActionResult>> verify(@PathVariable UUID operationId) {
        return service.verifyAction(UserContext.getUserId(), operationId, false).map(Results::success);
    }

    @PostMapping("/agent/v1/cart-operations/{operationId}/continue")
    public Mono<Result<CartOperationActionResult>> resume(@PathVariable UUID operationId) {
        String user = UserContext.getUserId();
        return Mono.using(() -> {
            CartOperation op = store.get(operationId, user);
            return runGate.acquireConversation(user, op.conversationId());
        }, release -> service.verifyAction(user, operationId, true).map(Results::success), Runnable::run)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/agent/v1/cart-operations/{operationId}/stop")
    public Result<CartOperationActionResult> stop(@PathVariable UUID operationId) {
        return Results.success(CartOperationActionResult.completed(store.stopOperation(operationId, UserContext.getUserId())));
    }
}

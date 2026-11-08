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

import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceContextKeys;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CartOperationServiceTest {
    private CartOperationStore store;
    private McpClientWrapper client;
    private CartOperationService service;
    private AgentMcpClients clients;
    private CartOperation pending;
    private AtomicReference<CartOperation> current;

    @BeforeEach void setup() {
        store = mock(CartOperationStore.class);
        client = mock(McpClientWrapper.class);
        clients = mock(AgentMcpClients.class);
        var meta = Map.<String, Object>of("com.nageoffer.ragent/cartReliabilityVersion", 1);
        for (String name : new String[]{"update_cart_item", "add_cart_item_once", "query_cart_operation"}) {
            var tool = Tool.builder().name(name).meta(meta)
                    .annotations(new ToolAnnotations(null, true, false, true, false, null)).build();
            when(clients.get(name)).thenReturn(new AgentMcpClients.RemoteTool(tool, client));
        }
        pending = new CartOperation(UUID.randomUUID(), "u", "c", "r", "A", 1, "auth", "task",
                "PENDING", 0, null, null, null, null, null, null);
        current = new AtomicReference<>(pending);
        when(store.get(pending.operationId(), "u")).thenAnswer(invocation -> current.get());
        when(store.authorizationValid(any())).thenReturn(true);
        service = new CartOperationService(store, clients);
    }

    @Test void listReadsProductNamesWithoutUpdatingOperations() {
        when(store.list("u", "c")).thenReturn(List.of(pending));
        var tool = Tool.builder().name("search_product").build();
        when(clients.get("search_product")).thenReturn(new AgentMcpClients.RemoteTool(tool, client));
        when(client.callTool(eq("search_product"), anyMap(), anyMap())).thenReturn(Mono.just(
                CallToolResult.builder().structuredContent(Map.of("productNames", Map.of("A", "测试商品"))).build()));
        assertThat(service.list("u", "c").get(0).productName()).isEqualTo("测试商品");
        verify(store, never()).applyReceipt(any(), anyMap());
    }

    @Test void listStillReturnsOperationsWhenProductLookupFails() {
        when(store.list("u", "c")).thenReturn(List.of(pending));
        when(clients.get("search_product")).thenReturn(new AgentMcpClients.RemoteTool(
                Tool.builder().name("search_product").build(), client));
        when(client.callTool(eq("search_product"), anyMap(), anyMap()))
                .thenReturn(Mono.error(new IllegalStateException("unavailable")));
        assertThat(service.list("u", "c").get(0).skuCode()).isEqualTo("A");
        assertThat(service.list("u", "c").get(0).productName()).isNull();
    }

    private ToolUseBlock approvedCall() {
        return ToolUseBlock.builder().id("cart-call").name("update_cart_item")
                .input(Map.of("skuCode", "A", "action", "add", "quantity", 1)).build();
    }

    private ToolCallParam request(ToolUseBlock call, CartRunControl control) {
        RuntimeContext ctx = RuntimeContext.builder().userId("u").sessionId("c").build();
        ctx.put(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID, "r");
        ctx.put(AgentTraceContextKeys.CONFIRM_MESSAGE_ID, "auth");
        ctx.put(AgentTraceContextKeys.TASK_ID, "task");
        ctx.put(CartRunControl.class, control);
        return ToolCallParam.builder().toolUseBlock(call).input(call.getInput()).runtimeContext(ctx).build();
    }

    @Test void disconnectBeforeAcceptanceDoesNotCreateOperationOrReserveWrite() {
        var call = approvedCall();
        var control = new CartRunControl(store, "task", List.of(new ConfirmResult(true, call)));
        control.markDisconnected();
        service.call(request(call, control), client).block(Duration.ofSeconds(5));
        verify(store, never()).accept(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
        verify(store, never()).reserveWrite(any(), any(), anyBoolean(), anyString());
        verify(store, never()).stopTask(anyString(), anyBoolean());
        verifyNoInteractions(client);
    }

    @Test void disconnectAfterAcceptanceDoesNotStartFirstAttempt() {
        var call = approvedCall();
        var control = new CartRunControl(store, "task", List.of(new ConfirmResult(true, call)));
        when(store.accept(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenAnswer(invocation -> { control.markDisconnected(); return pending; });
        when(store.acquire(eq(pending.operationId()), eq("u"), any())).thenReturn(true);
        service.call(request(call, control), client).block(Duration.ofSeconds(5));
        verify(store, never()).reserveWrite(any(), any(), anyBoolean(), anyString());
        verify(store, never()).stopTask(anyString(), anyBoolean());
        verifyNoInteractions(client);
    }

    @Test void disconnectWhileCheckingAuthorizationDoesNotReserveAnotherAttempt() {
        var call = approvedCall();
        var control = new CartRunControl(store, "task", List.of(new ConfirmResult(true, call)));
        when(store.accept(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(pending);
        when(store.acquire(eq(pending.operationId()), eq("u"), any())).thenReturn(true);
        when(store.authorizationValid(pending)).thenAnswer(invocation -> {
            control.markDisconnected();
            return true;
        });
        service.call(request(call, control), client).block(Duration.ofSeconds(5));
        verify(store, never()).reserveWrite(any(), any(), anyBoolean(), anyString());
        verify(store, never()).maySend(any(), any());
        verifyNoInteractions(client);
    }

    @Test void disconnectAfterBudgetReservationDoesNotSendToMcp() {
        var call = approvedCall();
        var control = new CartRunControl(store, "task", List.of(new ConfirmResult(true, call)));
        when(store.accept(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(pending);
        when(store.acquire(eq(pending.operationId()), eq("u"), any())).thenReturn(true);
        when(store.reserveWrite(eq(pending.operationId()), any(), eq(false), eq("task")))
                .thenAnswer(invocation -> { control.markDisconnected(); return 1; });
        service.call(request(call, control), client).block(Duration.ofSeconds(5));
        verify(store).reserveWrite(eq(pending.operationId()), any(), eq(false), eq("task"));
        verify(store).finishAttempt(pending.operationId(), 1, false);
        verify(store, never()).maySend(any(), any());
        verify(store, never()).stopTask(anyString(), anyBoolean());
        verifyNoInteractions(client);
    }

    @Test void residualLeaseReturnsDeferralThenExpiresIntoReadOnlyVerification() {
        Instant available = Instant.now().plusSeconds(40);
        when(store.acquire(eq(pending.operationId()), eq("u"), any())).thenReturn(false, true);
        when(store.nextVerificationAt(pending.operationId(), "u")).thenReturn(available);
        CartOperationActionResult waiting = service.verifyAction("u", pending.operationId(), false).block(Duration.ofSeconds(5));
        assertThat(waiting.deferred()).isTrue();
        assertThat(waiting.verificationStarted()).isFalse();
        assertThat(waiting.retryAt()).isEqualTo(available);
        verifyNoInteractions(client);

        when(store.beginVerification(eq(pending.operationId()), any())).thenReturn(true);
        when(store.remainingMillis(eq(pending.operationId()), any())).thenReturn(5000L);
        when(store.reserveQuery(eq(pending.operationId()), any())).thenReturn(true);
        when(client.callTool(eq("query_cart_operation"), anyMap(), anyMap()))
                .thenReturn(Mono.just(CallToolResult.builder().structuredContent(Map.of("status", "SUCCEEDED")).build()));
        doAnswer(invocation -> {
            current.set(new CartOperation(pending.operationId(), "u", "c", "r", "A", 1, "auth", "task",
                    "SUCCEEDED", 0, null, null, "已加购", 1, Instant.now(), "测试商品"));
            return null;
        }).when(store).applyReceipt(eq(pending), anyMap());
        CartOperationActionResult completed = service.verifyAction("u", pending.operationId(), false).block(Duration.ofSeconds(5));
        assertThat(completed.operation().status()).isEqualTo("SUCCEEDED");
        assertThat(completed.verificationStarted()).isTrue();
        assertThat(completed.deferred()).isFalse();
        assertThat(completed.retryAt()).isNull();
        verify(client, never()).callTool(eq("add_cart_item_once"), anyMap(), anyMap());
    }

    @Test void exhaustedQueryBudgetReturnsRetryTimeAndNoticeWithoutWriting() {
        Instant available = Instant.now().plusSeconds(25);
        when(store.acquire(eq(pending.operationId()), eq("u"), any())).thenReturn(true);
        when(store.beginVerification(eq(pending.operationId()), any())).thenReturn(false);
        when(store.nextVerificationAt(pending.operationId(), "u")).thenReturn(available);
        CartOperationActionResult result = service.verifyAction("u", pending.operationId(), true).block(Duration.ofSeconds(5));
        assertThat(result.deferred()).isTrue();
        assertThat(result.verificationStarted()).isFalse();
        assertThat(result.retryAt()).isEqualTo(available);
        assertThat(result.notice()).contains("等待");
        assertThat(result.operation().writeAttempts()).isZero();
        verify(store).release(eq(pending.operationId()), any());
        verifyNoInteractions(client);
    }

    @Test void completedOperationDoesNotAskClientToRetry() {
        current.set(new CartOperation(pending.operationId(), "u", "c", "r", "A", 1, "auth", "task",
                "REJECTED", 1, null, null, "已下架", null, Instant.now(), "测试商品"));
        CartOperationActionResult result = service.verifyAction("u", pending.operationId(), true).block(Duration.ofSeconds(5));
        assertThat(result.deferred()).isFalse();
        assertThat(result.retryAt()).isNull();
        verify(store, never()).acquire(any(), anyString(), any());
        verifyNoInteractions(client);
    }
}

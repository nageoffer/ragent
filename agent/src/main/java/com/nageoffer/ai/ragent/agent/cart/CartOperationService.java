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
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpMeta;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceContextKeys;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class CartOperationService {
    public static final String WRITE_TOOL = "update_cart_item";
    public static final String RPC_WRITE_TOOL = "add_cart_item_once";
    public static final String QUERY_TOOL = "query_cart_operation";
    private static final String META_OPERATION = "com.nageoffer.ragent/cartOperationId";
    private final CartOperationStore store;
    private final AgentMcpClients clients;

    public Mono<ToolResultBlock> call(ToolCallParam param, McpClientWrapper client) {
        RuntimeContext ctx = param.getRuntimeContext();
        return Mono.fromCallable(() -> {
            if (ctx == null) throw new ClientException("缺少本轮操作上下文，未开始加购");
            CartRunControl control = ctx.get(CartRunControl.class);
            String round = ctx.get(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID);
            String authorization = ctx.get(AgentTraceContextKeys.CONFIRM_MESSAGE_ID);
            String task = ctx.get(AgentTraceContextKeys.TASK_ID);
            if (round == null || authorization == null || control == null || !control.approved(param.getToolUseBlock())
                    || !param.getToolUseBlock().getInput().equals(param.getInput())) {
                throw new ClientException("没有找到本次加购的有效授权，未开始执行");
            }
            requireCapability(client);
            String sku = String.valueOf(param.getInput().getOrDefault("skuCode", "")).trim();
            if (sku.isEmpty()) throw new ClientException("请先确认商品 SKU");
            int quantity;
            try { quantity = new BigDecimal(String.valueOf(param.getInput().get("quantity"))).intValueExact(); }
            catch (RuntimeException e) { throw new ClientException("增加量必须是 1 到 99 的整数"); }
            if (quantity < 1 || quantity > 99) throw new ClientException("增加量必须是 1 到 99 的整数");
            CartOperation previous = store.unresolved(ctx.getUserId(), sku);
            if (previous != null && (!previous.roundId().equals(round) || !previous.conversationId().equals(ctx.getSessionId()))) {
                CartOperation checked = run(previous, client, false, true, task, control).operation();
                return ToolResultBlock.text("此次没有创建新的加购，先核实之前同款操作。原操作结果："
                        + (checked.message() == null ? "仍待核实" : checked.message())
                        + (checked.terminal() ? "。如仍需额外加购，请用户明确再次确认。" : "。请勿重复加购。"));
            }
            CartOperation op;
            synchronized (control) {
                if (control.cancelled()) return ToolResultBlock.text("用户已停止，没有开始新的加购");
                op = store.accept(ctx.getUserId(), ctx.getSessionId(), round, sku, quantity, authorization, task);
            }
            op = run(op, client, true, false, task, control).operation();
            return toolResult(op);
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(e -> {
            log.warn("可靠加购协调未完成", e);
            return Mono.just(ToolResultBlock.error(e instanceof ClientException ? e.getMessage()
                    : "加购结果尚未确定，请在操作条目中核实；不要重复加购"));
        });
    }

    public List<CartOperation> list(String user, String conversation) {
        List<CartOperation> operations = store.list(user, conversation);
        var remote = clients.get("search_product");
        if (operations.isEmpty() || remote == null) return operations;
        List<String> skus = operations.stream().map(CartOperation::skuCode).distinct().toList();
        Map<String, String> names = new java.util.HashMap<>();
        // 商品查询每批最多返回 40 个 SKU，分批读取避免历史记录缺名。
        for (int start = 0; start < skus.size(); start += 40) {
            List<String> batch = skus.subList(start, Math.min(start + 40, skus.size()));
            try {
                CallToolResult result = remote.client().callTool("search_product",
                        Map.of("skuCodes", String.join(",", batch)), AgentMcpMeta.ofUser(user))
                        .block(Duration.ofSeconds(2));
                if (result != null && !Boolean.TRUE.equals(result.isError())
                        && result.structuredContent() instanceof Map<?, ?> content
                        && content.get("productNames") instanceof Map<?, ?> products) {
                    for (String sku : batch) {
                        if (products.get(sku) instanceof String name && !name.isBlank()) names.put(sku, name);
                    }
                }
            } catch (RuntimeException e) {
                log.debug("商品名称暂不可用，回退显示 SKU", e);
            }
        }
        return operations.stream().map(op -> op.withProductName(names.get(op.skuCode()))).toList();
    }

    public Mono<CartOperation> verify(String user, UUID id, boolean write) {
        return verifyAction(user, id, write).map(CartOperationActionResult::operation);
    }

    public Mono<CartOperationActionResult> verifyAction(String user, UUID id, boolean write) {
        String task = "continue-" + UUID.randomUUID();
        CartRunControl control = new CartRunControl(store, task, List.of());
        return Mono.fromCallable(() -> {
            CartOperation op = store.get(id, user);
            if (op.terminal()) return CartOperationActionResult.completed(op);
            var remote = clients.get(WRITE_TOOL);
            if (remote == null) throw new ClientException("加购服务暂不可用，请稍后核实");
            requireCapability(remote.client());
            if (write && !authorized(op)) throw new ClientException("原授权无法核实，不能继续写执行");
            return run(op, remote.client(), write, true, task, write ? control : null);
        }).subscribeOn(Schedulers.boundedElastic()).doOnCancel(() -> {
            if (write) { control.markDisconnected(); control.cancel(); }
        });
    }

    public Mono<ToolResultBlock> verifyForConversation(String user, String conversation, String sku) {
        List<CartOperation> candidates = store.list(user, conversation).stream()
                .filter(op -> sku == null || sku.isBlank() || op.skuCode().equals(sku)).toList();
        if (candidates.isEmpty()) return Mono.just(ToolResultBlock.text("当前会话没有可核实的可靠加购操作，不会补发旧历史请求"));
        if (candidates.size() != 1) return Mono.just(ToolResultBlock.text("有多次加购，请让用户选择会话中的具体加购结果条目进行核实，不能猜测或创建新操作"));
        CartOperation op = candidates.get(0);
        return verify(user, op.operationId(), false).map(this::toolResult);
    }

    private void requireCapability(McpClientWrapper client) {
        var query = clients.get(QUERY_TOOL);
        var write = clients.get(WRITE_TOOL);
        var rpcWrite = clients.get(RPC_WRITE_TOOL);
        if (rpcWrite == null || rpcWrite.client() != client || !protocol(rpcWrite.definition().meta())
                || write == null || write.client() != client || !protocol(write.definition().meta())
                || query == null || !protocol(query.definition().meta()) || query.client() != client || query.definition().annotations() == null
                || !Boolean.TRUE.equals(query.definition().annotations().readOnlyHint())) {
            throw new ClientException("可靠加购与回执查询能力尚未配套启用，未发起写请求");
        }
    }

    private boolean protocol(Map<String, Object> meta) {
        return meta != null && meta.get("com.nageoffer.ragent/cartReliabilityVersion") instanceof Number version
                && version.intValue() == 1;
    }

    private boolean authorized(CartOperation op) {
        return store.authorizationValid(op);
    }

    private CartOperationActionResult run(CartOperation op, McpClientWrapper client, boolean write, boolean recovered,
                                         String task, CartRunControl control) {
        UUID owner = UUID.randomUUID();
        if (!store.acquire(op.operationId(), op.userId(), owner)) {
            CartOperation latest = store.get(op.operationId(), op.userId());
            if (latest.terminal()) return CartOperationActionResult.completed(latest);
            return new CartOperationActionResult(latest, false, true,
                    store.nextVerificationAt(op.operationId(), op.userId()), "正在等待此前请求结束，请稍后再核实或继续");
        }
        boolean started = false;
        try {
            if (write && !recovered && op.writeAttempts() == 0) send(op, client, owner, false, task, control, 8000);
            op = store.get(op.operationId(), op.userId());
            if (!op.terminal() && !cancelled(control) && store.beginVerification(op.operationId(), owner)) {
                // 单次 block 均有超时，总核实轮次在数据库中计数，刷新不重置。
                while (!Thread.currentThread().isInterrupted() && !cancelled(control)) {
                    long remaining = store.remainingMillis(op.operationId(), owner);
                    if (remaining <= 0 || !store.reserveQuery(op.operationId(), owner)) break;
                    started = true;
                    try {
                        CallToolResult result = client.callTool(QUERY_TOOL,
                                Map.of("operationId", op.operationId().toString()), AgentMcpMeta.ofUser(op.userId()))
                                .block(Duration.ofMillis(Math.min(5000, remaining)));
                        apply(op, result);
                    } catch (RuntimeException e) { log.info("加购核实暂未完成 operationId={}", op.operationId()); }
                    op = store.get(op.operationId(), op.userId());
                    if (op.terminal() || cancelled(control)) break;
                    remaining = store.remainingMillis(op.operationId(), owner);
                    if (write && op.canContinue() && remaining > 0 && authorized(op)) {
                        send(op, client, owner, recovered, task, control, Math.min(8000, remaining));
                        op = store.get(op.operationId(), op.userId());
                        if (op.terminal()) break;
                    }
                    remaining = store.remainingMillis(op.operationId(), owner);
                    if (remaining <= 1000) break;
                    try { Thread.sleep(1000); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
        } finally {
            store.release(op.operationId(), owner);
        }
        CartOperation latest = store.get(op.operationId(), op.userId());
        var retryAt = latest.terminal() ? null : store.nextVerificationAt(op.operationId(), op.userId());
        boolean deferred = !latest.terminal() && !started && !cancelled(control);
        String notice = retryAt != null ? "本轮核实已到次数上限或尚未到查询间隔，请等待后再核实或继续" : null;
        if (deferred && notice == null) notice = "本次尚未开始核实，请稍后重试";
        return new CartOperationActionResult(latest, started, deferred, retryAt, notice);
    }

    private void send(CartOperation op, McpClientWrapper client, UUID owner, boolean recovered,
                      String task, CartRunControl control, long timeout) {
        if (cancelled(control) || Thread.currentThread().isInterrupted() || !authorized(op)) return;
        int attempt;
        if (control == null) {
            attempt = store.reserveWrite(op.operationId(), owner, recovered, task);
        } else {
            synchronized (control) {
                if (control.cancelled()) return;
                attempt = store.reserveWrite(op.operationId(), owner, recovered, task);
            }
        }
        if (attempt == 0) return;
        boolean response = false;
        try {
            if (control == null) {
                if (!store.maySend(op.operationId(), owner)) return;
            } else {
                // 与断连标记串行确定“尝试开始”的先后；锁外只执行已取得资格的请求。
                synchronized (control) {
                    if (control.cancelled() || !store.maySend(op.operationId(), owner)) return;
                }
            }
            CallToolResult result = client.callTool(RPC_WRITE_TOOL,
                    Map.of("skuCode", op.skuCode(), "action", "add", "quantity", op.quantity()),
                    Map.of(AgentMcpMeta.USER_ID_KEY, op.userId(), META_OPERATION, op.operationId().toString()))
                    .block(Duration.ofMillis(timeout));
            response = result != null;
            apply(op, result);
        } catch (RuntimeException e) { log.info("加购写结果尚未确定 operationId={}, attempt={}", op.operationId(), attempt); }
        finally { store.finishAttempt(op.operationId(), attempt, response); }
    }

    private boolean cancelled(CartRunControl control) { return control != null && control.cancelled(); }
    private void apply(CartOperation op, CallToolResult result) {
        if (result != null && result.structuredContent() instanceof Map<?, ?> receipt) store.applyReceipt(op, receipt);
    }
    private ToolResultBlock toolResult(CartOperation op) {
        String message = op.message();
        if (!op.terminal()) message = "暂时无法确认本次加购是否成功，请核实原操作，不要重复加购";
        if ("CANCELLED".equals(op.status())) message = "已取消，本次未执行";
        if (op.stoppedAt() != null && "SUCCEEDED".equals(op.status())) message += "；停止请求未撤销已提交操作";
        return "REJECTED".equals(op.status()) ? ToolResultBlock.error(message) : ToolResultBlock.text(message);
    }
}

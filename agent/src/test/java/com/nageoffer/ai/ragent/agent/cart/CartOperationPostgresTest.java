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
import io.modelcontextprotocol.spec.McpSchema.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfSystemProperty(named="cart.test.url", matches=".+")
class CartOperationPostgresTest {
    String schema;
    DriverManagerDataSource ds;
    JdbcTemplate jdbc;
    CartOperationStore store;
    @BeforeEach void setup() {
        schema="cart_agent_test_"+UUID.randomUUID().toString().replace("-", "");
        String url=System.getProperty("cart.test.url");
        ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,
                System.getProperty("cart.test.user","postgres"),System.getProperty("cart.test.password","postgres"));
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA "+schema);
        jdbc.execute("CREATE TABLE t_agent_conversation(conversation_id text,user_id text,deleted int DEFAULT 0)");
        jdbc.execute("CREATE TABLE t_agent_message(id text,user_id text,conversation_id text,blocks jsonb,deleted int DEFAULT 0)");
        new ResourceDatabasePopulator(new FileSystemResource("../resources/database/upgrades/v2.0.0/261022_agent_cart_reliability.sql")).execute(ds);
        jdbc.update("INSERT INTO t_agent_conversation(conversation_id,user_id) VALUES ('c','u'),('c2','u')");
        jdbc.update("INSERT INTO t_agent_message(id,user_id,conversation_id,blocks) VALUES ('auth','u','c','[{\"kind\":\"confirm\",\"status\":\"approved\"}]')");
        store=new CartOperationStore(jdbc,new DataSourceTransactionManager(ds));
    }
    @AfterEach void cleanup() { if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE"); }
    CartOperation accept(String round,String sku,int qty) { return store.accept("u","c",round,sku,qty,"auth","task"); }
    Map<String,Object> receipt(CartOperation op,String status) {
        return Map.of("operationId",op.operationId().toString(),"userId","u","skuCode",op.skuCode(),
                "action","add","quantity",op.quantity(),"status",status,"quantityAfter",1,
                "message","确定结果","executedAt",Instant.now().toString());
    }
    @Test void sameRoundIdentityAndCrossConversationUnresolvedGuard() throws Exception {
        var pool=Executors.newFixedThreadPool(10);
        Set<UUID> ids=ConcurrentHashMap.newKeySet();
        try {
            var jobs=new ArrayList<Future<?>>();
            for(int i=0;i<20;i++)jobs.add(pool.submit(()->ids.add(accept("r","A",1).operationId())));
            for(var job:jobs)job.get(15,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(ids).hasSize(1);
        assertThatThrownBy(()->accept("r","A",2)).hasMessageContaining("冲突");
        assertThatThrownBy(()->store.accept("u","c2","r2","A",1,"auth","task2")).hasMessageContaining("先核实");
        assertThat(accept("r2","B",1).skuCode()).isEqualTo("B");
        CartOperation original=store.get(ids.iterator().next(),"u");
        store.applyReceipt(original,receipt(original,"SUCCEEDED"));
        assertThat(accept("r3","A",1).operationId()).isNotEqualTo(original.operationId());
    }
    @Test void sharedBudgetSurvivesNewStoreAndLeasePreventsCompetingWriters() {
        var op=accept("r","A",1);
        UUID owner=UUID.randomUUID();
        assertThat(store.acquire(op.operationId(),"u",owner)).isTrue();
        assertThat(store.acquire(op.operationId(),"u",UUID.randomUUID())).isFalse();
        for(int n=1;n<=3;n++)assertThat(store.reserveWrite(op.operationId(),owner,false,"task")).isEqualTo(n);
        store=new CartOperationStore(jdbc,new DataSourceTransactionManager(ds));
        assertThat(store.reserveWrite(op.operationId(),owner,false,"task")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_agent_cart_attempt",Integer.class)).isEqualTo(3);
        assertThat(store.get(op.operationId(),"u").writeAttempts()).isEqualTo(3);
        store.release(op.operationId(),owner);
    }
    @Test void queryBudgetCannotBeResetWithinCurrentRound() {
        var op=accept("r","A",1); UUID owner=UUID.randomUUID();store.acquire(op.operationId(),"u",owner);
        assertThat(store.beginVerification(op.operationId(),owner)).isTrue();
        for(int n=0;n<6;n++) {
            assertThat(store.reserveQuery(op.operationId(),owner)).isTrue();
            assertThat(store.reserveQuery(op.operationId(),owner)).isFalse();
            jdbc.update("UPDATE t_agent_cart_operation SET last_verify_at=now()-interval '2 seconds'");
        }
        assertThat(store.beginVerification(op.operationId(),owner)).isFalse();
        assertThat(store.reserveQuery(op.operationId(),owner)).isFalse();
        assertThat(store.remainingMillis(op.operationId(),owner)).isPositive();
    }
    @Test void stopBeforeSendAndLateSuccessAfterStop() {
        var before=accept("r","A",1);store.stopTask("task",false);
        assertThat(store.get(before.operationId(),"u").status()).isEqualTo("CANCELLED");
        var op=accept("r2","A",1); UUID owner=UUID.randomUUID();store.acquire(op.operationId(),"u",owner);
        store.reserveWrite(op.operationId(),owner,false,"task");
        store.stopTask("task",false);
        assertThat(store.maySend(op.operationId(),owner)).isFalse();
        assertThat(store.reserveWrite(op.operationId(),owner,true,"task")).isZero();
        store.applyReceipt(op,receipt(op,"SUCCEEDED"));
        var result=store.get(op.operationId(),"u");
        assertThat(result.status()).isEqualTo("SUCCEEDED");assertThat(result.stoppedAt()).isNotNull();
        store.applyReceipt(op,receipt(op,"REJECTED"));
        assertThat(store.get(op.operationId(),"u").status()).isEqualTo("SUCCEEDED");
    }
    @Test void permissionDeletionAndReceiptValidation() {
        var op=accept("r","A",1);
        assertThatThrownBy(()->store.get(op.operationId(),"other")).hasMessageContaining("无权");
        assertThatThrownBy(()->store.guardDeletion("u","c")).hasMessageContaining("待核实");
        var wrong=new HashMap<>(receipt(op,"SUCCEEDED"));wrong.put("quantity",2);
        store.applyReceipt(op,wrong);
        assertThat(store.get(op.operationId(),"u").terminal()).isFalse();
        assertThat(store.authorizationValid(op)).isTrue();
        jdbc.update("UPDATE t_agent_message SET blocks='[]'");assertThat(store.authorizationValid(op)).isFalse();
    }
    @Test void coordinatorRetriesAreBoundedAndRecoveryNeverWritesOnItsOwn() {
        var client=mock(McpClientWrapper.class);var clients=mock(AgentMcpClients.class);
        var meta=Map.<String,Object>of("com.nageoffer.ragent/cartReliabilityVersion",1);
        when(clients.get("query_cart_operation")).thenReturn(new AgentMcpClients.RemoteTool(
                Tool.builder().name("query_cart_operation").meta(meta).annotations(new ToolAnnotations(null,true,false,true,false,null)).build(),client));
        when(clients.get("update_cart_item")).thenReturn(new AgentMcpClients.RemoteTool(
                Tool.builder().name("update_cart_item").meta(meta).build(),client));
        when(clients.get("add_cart_item_once")).thenReturn(new AgentMcpClients.RemoteTool(
                Tool.builder().name("add_cart_item_once").meta(Map.of("com.nageoffer.ragent/cartReliabilityVersion",1)).build(),client));
        when(client.callTool(eq("add_cart_item_once"),anyMap(),anyMap())).thenReturn(Mono.error(new IllegalStateException("timeout")));
        when(client.callTool(eq("query_cart_operation"),anyMap(),anyMap())).thenReturn(Mono.just(
                CallToolResult.builder().structuredContent(Map.of("status","NOT_FOUND")).build()));
        var service=new CartOperationService(store,clients);
        var call=ToolUseBlock.builder().id("call").name("update_cart_item")
                .input(Map.of("skuCode","A","action","add","quantity",1)).build();
        var ctx=RuntimeContext.builder().userId("u").sessionId("c").build();
        ctx.put(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID,"r");ctx.put(AgentTraceContextKeys.CONFIRM_MESSAGE_ID,"auth");
        ctx.put(AgentTraceContextKeys.TASK_ID,"task");ctx.put(CartRunControl.class,new CartRunControl(store,"task",List.of(new ConfirmResult(true,call))));
        // 工具参数与用户确认的原参数不一致，必须在保存操作前拒绝。
        service.call(ToolCallParam.builder().toolUseBlock(call).input(Map.of("skuCode","A","action","add","quantity",2))
                .runtimeContext(ctx).build(),client).block(Duration.ofSeconds(10));
        assertThat(store.list("u","c")).isEmpty();
        service.call(ToolCallParam.builder().toolUseBlock(call).input(call.getInput()).runtimeContext(ctx).build(),client)
                .block(Duration.ofSeconds(15));
        var op=store.list("u","c").get(0);
        assertThat(op.status()).isEqualTo("PENDING");assertThat(op.writeAttempts()).isEqualTo(3);
        verify(client,times(3)).callTool(eq("add_cart_item_once"),anyMap(),anyMap());
        verify(client,times(6)).callTool(eq("query_cart_operation"),anyMap(),anyMap());
        var unsent=accept("r2","B",1);
        service.verify("u",unsent.operationId(),false).block(Duration.ofSeconds(15));
        assertThat(store.get(unsent.operationId(),"u").writeAttempts()).isZero();
        assertThat(store.get(unsent.operationId(),"u").status()).isEqualTo("PENDING");
        verify(client,times(3)).callTool(eq("add_cart_item_once"),anyMap(),anyMap());
    }

    @Test void recoveryDefersForResidualLeaseThenQueriesWhenLeaseExpires() {
        var op=accept("r","A",1);
        UUID owner=UUID.randomUUID();
        store.acquire(op.operationId(),"u",owner);
        var client=mock(McpClientWrapper.class);var clients=mock(AgentMcpClients.class);
        var meta=Map.<String,Object>of("com.nageoffer.ragent/cartReliabilityVersion",1);
        for(String name:List.of("update_cart_item","add_cart_item_once","query_cart_operation")) {
            when(clients.get(name)).thenReturn(new AgentMcpClients.RemoteTool(Tool.builder().name(name).meta(meta)
                    .annotations(new ToolAnnotations(null,true,false,true,false,null)).build(),client));
        }
        var service=new CartOperationService(store,clients);
        var waiting=service.verifyAction("u",op.operationId(),false).block(Duration.ofSeconds(5));
        assertThat(waiting.deferred()).isTrue();assertThat(waiting.retryAt()).isAfter(Instant.now());
        verifyNoInteractions(client);
        jdbc.update("UPDATE t_agent_cart_operation SET lease_until=now()-interval '1 second'");
        when(client.callTool(eq("query_cart_operation"),anyMap(),anyMap())).thenReturn(Mono.just(
                CallToolResult.builder().structuredContent(receipt(op,"SUCCEEDED")).build()));
        var completed=service.verifyAction("u",op.operationId(),false).block(Duration.ofSeconds(5));
        assertThat(completed.verificationStarted()).isTrue();assertThat(completed.deferred()).isFalse();
        assertThat(completed.operation().status()).isEqualTo("SUCCEEDED");
        assertThat(completed.operation().writeAttempts()).isZero();
        verify(client,never()).callTool(eq("add_cart_item_once"),anyMap(),anyMap());
    }

    @Test void continueCannotResetExhaustedVerificationBudgetAndReturnsWaitTime() {
        var op=accept("r","A",1);
        jdbc.update("UPDATE t_agent_cart_operation SET verify_count=6,verify_deadline=now()+interval '25 seconds'");
        var client=mock(McpClientWrapper.class);var clients=mock(AgentMcpClients.class);
        for(String name:List.of("update_cart_item","add_cart_item_once","query_cart_operation")) {
            when(clients.get(name)).thenReturn(new AgentMcpClients.RemoteTool(Tool.builder().name(name)
                    .meta(Map.of("com.nageoffer.ragent/cartReliabilityVersion",1))
                    .annotations(new ToolAnnotations(null,true,false,true,false,null)).build(),client));
        }
        var result=new CartOperationService(store,clients).verifyAction("u",op.operationId(),true).block(Duration.ofSeconds(5));
        assertThat(result.deferred()).isTrue();assertThat(result.verificationStarted()).isFalse();
        assertThat(result.retryAt()).isAfter(Instant.now());assertThat(result.notice()).contains("等待");
        assertThat(result.operation().status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT verify_count FROM t_agent_cart_operation",Integer.class)).isEqualTo(6);
        assertThat(store.get(op.operationId(),"u").writeAttempts()).isZero();
        verifyNoInteractions(client);
    }

    @Test void forcedMainProcessExitLeavesAcceptedOperationRecoverable() throws Exception {
        String javaBin=java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString();
        Process process=new ProcessBuilder(javaBin,"-cp",System.getProperty("java.class.path"),
                "-Dcart.test.url="+System.getProperty("cart.test.url"),
                "-Dcart.test.user="+System.getProperty("cart.test.user","postgres"),
                "-Dcart.test.password="+System.getProperty("cart.test.password","postgres"),
                Worker.class.getName(),schema).redirectErrorStream(true).start();
        var reader=Executors.newSingleThreadExecutor();
        try {
            var ready=reader.submit(()->{
                var lines=new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
                String line;
                while((line=lines.readLine())!=null)if(line.equals("READY_TO_KILL"))return true;
                return false;
            });
            assertThat(ready.get(20,TimeUnit.SECONDS)).isTrue();
        } finally {
            process.destroyForcibly();assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();reader.shutdownNow();
        }
        store=new CartOperationStore(jdbc,new DataSourceTransactionManager(ds));
        var op=store.list("u","c").get(0);
        assertThat(op.quantity()).isEqualTo(1);
        assertThat(op.writeAttempts()).isZero();
        assertThat(op.status()).isEqualTo("PROCESSING");
        assertThat(store.authorizationValid(op)).isTrue();
        // 恢复入口只能核实；记录本身不能证明远端未执行。
        assertThat(op.terminal()).isFalse();
    }

    public static class Worker {
        public static void main(String[] args) throws Exception {
            String url=System.getProperty("cart.test.url");
            var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+args[0],
                    System.getProperty("cart.test.user","postgres"),System.getProperty("cart.test.password","postgres"));
            var store=new CartOperationStore(new JdbcTemplate(ds),new DataSourceTransactionManager(ds));
            store.accept("u","c","r","A",1,"auth","task");
            System.out.println("READY_TO_KILL");System.out.flush();Thread.sleep(60000);
        }
    }

    @Test void lostResponseIsVerifiedWithoutNewWriteAndRecoveryUsesOriginalIdentity() {
        var client=mock(McpClientWrapper.class);var clients=mock(AgentMcpClients.class);
        var query=Tool.builder().name("query_cart_operation").meta(Map.of("com.nageoffer.ragent/cartReliabilityVersion",1)).annotations(new ToolAnnotations(null,true,false,true,false,null)).build();
        when(clients.get("query_cart_operation")).thenReturn(new AgentMcpClients.RemoteTool(query,client));
        when(clients.get("update_cart_item")).thenReturn(new AgentMcpClients.RemoteTool(Tool.builder().name("update_cart_item").meta(Map.of("com.nageoffer.ragent/cartReliabilityVersion",1)).build(),client));
        when(clients.get("add_cart_item_once")).thenReturn(new AgentMcpClients.RemoteTool(
                Tool.builder().name("add_cart_item_once").meta(Map.of("com.nageoffer.ragent/cartReliabilityVersion",1)).build(),client));
        when(client.callTool(eq("add_cart_item_once"),anyMap(),anyMap())).thenReturn(Mono.error(new IllegalStateException("response lost")));
        when(client.callTool(eq("query_cart_operation"),anyMap(),anyMap())).thenAnswer(invocation->{
            var op=store.list("u","c").get(0);
            return Mono.just(CallToolResult.builder().structuredContent(receipt(op,"SUCCEEDED")).build());
        });
        var service=new CartOperationService(store,clients);
        var call=ToolUseBlock.builder().id("tool1").name("update_cart_item")
                .input(Map.of("skuCode","A","action","add","quantity",1)).build();
        var ctx=RuntimeContext.builder().userId("u").sessionId("c").build();
        ctx.put(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID,"r");ctx.put(AgentTraceContextKeys.CONFIRM_MESSAGE_ID,"auth");
        ctx.put(AgentTraceContextKeys.TASK_ID,"task");ctx.put(CartRunControl.class,new CartRunControl(store,"task",List.of(new ConfirmResult(true,call))));
        service.call(ToolCallParam.builder().toolUseBlock(call).input(call.getInput()).runtimeContext(ctx).build(),client).block(Duration.ofSeconds(10));
        var op=store.list("u","c").get(0);
        assertThat(op.status()).isEqualTo("SUCCEEDED");assertThat(op.writeAttempts()).isEqualTo(1);
        new CartOperationService(new CartOperationStore(jdbc,new DataSourceTransactionManager(ds)),clients)
                .verify("u",op.operationId(),false).block(Duration.ofSeconds(10));
        verify(client,times(1)).callTool(eq("add_cart_item_once"),anyMap(),anyMap());
    }
}

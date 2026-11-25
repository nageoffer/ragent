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

package com.nageoffer.ai.ragent.rag.core.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.GraphProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import okhttp3.OkHttpClient;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightRagClientTest {

    private MockWebServer server;
    private LightRagClient client;
    private SearchChannelProperties searchProperties;
    private GraphProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        properties = new GraphProperties();
        properties.getLightrag().setBaseUrl(server.url("/").toString());
        properties.getLightrag().setSyncPollIntervalMs(10);
        searchProperties = new SearchChannelProperties();
        client = new LightRagClient(new OkHttpClient(), objectMapper, properties, searchProperties);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    @Test
    @DisplayName("删库只命中库名精确归属的文档，不连带前缀重叠的别库")
    void deleteByCollectionMatchesExactCollectionOnly() throws Exception {
        // 回归：旧 contains("kb_") 谓词会把 kb_hr 的两篇文档一并选中，删 kb 连带删光 kb_hr 的图谱数据且不可逆
        server.enqueue(json("""
                {"statuses":{"processed":[
                  {"id":"doc-kb","file_path":"kb_1954071234567890100"},
                  {"id":"doc-kb-hr-1","file_path":"kb_hr_1954071234567890200"},
                  {"id":"doc-kb-hr-2","file_path":"kb_hr_1954071234567890300"}
                ]}}
                """));
        server.enqueue(json("{\"status\":\"deletion_started\"}"));
        server.enqueue(json("{\"busy\":false,\"destructive_busy\":false}"));

        client.deleteByCollection("kb");

        RecordedRequest listRequest = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(listRequest);
        assertEquals("/documents", listRequest.getTarget());

        RecordedRequest deleteRequest = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(deleteRequest, "kb 名下有文档，应发起删除请求");
        assertEquals("/documents/delete_document", deleteRequest.getTarget());
        assertEquals(List.of("doc-kb"), docIdsOf(deleteRequest));
    }

    @Test
    @DisplayName("检索证据按库名精确归属切分主份与补充份")
    void retrieveByScopeSplitsByExactCollection() {
        // 回归：旧 contains 谓词会把 kb_hr 的证据归入 kb 的主份，跨库证据混进定向主路
        server.enqueue(json("""
                {"response":"ctx","references":[
                  {"reference_id":"r0","file_path":"kb_hr_1954071234567890200","content":["别库证据"]},
                  {"reference_id":"r1","file_path":"kb_1954071234567890100","content":["本库证据"]}
                ]}
                """));

        GraphEvidence evidence = client.retrieveByScope("报销流程", "mix", 10, List.of("kb"));

        assertEquals(List.of("r1"), ids(evidence.matched()));
        assertEquals(List.of("r0"), ids(evidence.unmatched()));
        assertEquals("1954071234567890100", evidence.matched().get(0).getDocId());
    }

    @Test
    @DisplayName("过滤生效时 references 缺失的兜底上下文被丢弃而非无主入池")
    void fallbackContextIsDroppedWhenFilteringByCollection() {
        // 兜底块无 file_path、无法归属，放行等于绕过有效库过滤
        server.enqueue(json("{\"response\":\"整段上下文\"}"));

        GraphEvidence evidence = client.retrieveByScope("报销流程", "mix", 10, List.of("kb"));

        assertTrue(evidence.matched().isEmpty());
        assertTrue(evidence.unmatched().isEmpty());
    }

    @Test
    @DisplayName("删除链路不受检索通道预算限制")
    void deletionIsNotBoundByChannelBudget() throws Exception {
        // 回归：检索预算若污染删除链路，大库文档枚举超预算会中断删除，静默留下图谱残留
        searchProperties.getChannels().setTimeoutMs(200);
        server.enqueue(json("""
                {"statuses":{"processed":[
                  {"id":"doc-kb","file_path":"kb_1954071234567890100"}
                ]}}
                """).newBuilder().bodyDelay(1, TimeUnit.SECONDS).build());
        server.enqueue(json("{\"status\":\"deletion_started\"}"));
        server.enqueue(json("{\"busy\":false,\"destructive_busy\":false}"));

        client.deleteByCollection("kb");

        server.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest deleteRequest = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(deleteRequest, "删除请求应照常发出，不被 200ms 检索预算掐断");
        assertEquals(List.of("doc-kb"), docIdsOf(deleteRequest));
    }

    @Test
    @DisplayName("客户端超时取通道级预算，超限调用降级为空证据")
    void clientTimeoutFollowsChannelBudget() {
        searchProperties.getChannels().setTimeoutMs(200);
        server.enqueue(json("{\"response\":\"ctx\"}").newBuilder().bodyDelay(1, TimeUnit.SECONDS).build());

        GraphEvidence evidence = client.retrieveByScope("报销流程", "mix", 10, List.of());

        assertTrue(evidence.matched().isEmpty());
    }

    @Test
    @DisplayName("删除返回 busy 时有限重试，deletion_started 后等后台删除真正完成才返回")
    void deleteByDocWaitsUntilBackgroundDeletionFinishes() throws Exception {
        // 回归：deletion_started 只表示后台任务已启动，紧接着的重建写入会撞上 destructive_busy 返回 409
        server.enqueue(json("""
                {"statuses":{"processed":[
                  {"id":"doc-target","file_path":"kb_1954071234567890100"}
                ]}}
                """));
        server.enqueue(json("{\"status\":\"busy\",\"message\":\"Pipeline is busy\"}"));
        server.enqueue(json("{\"status\":\"deletion_started\"}"));
        server.enqueue(json("{\"busy\":true,\"destructive_busy\":true}"));
        server.enqueue(json("{\"busy\":false,\"destructive_busy\":false}"));

        client.deleteByDoc("1954071234567890100");

        assertEquals(List.of("/documents", "/documents/delete_document", "/documents/delete_document",
                "/documents/pipeline_status", "/documents/pipeline_status"), takeTargets(5));
        assertEquals(0, server.getRequestCount() - 5, "删除完成后不应再有多余请求");
    }

    @Test
    @DisplayName("后台删除超过等待上限仍未完成时抛出异常，而不是当作成功")
    void deleteByDocFailsWhenDeletionNeverFinishes() {
        properties.getLightrag().setSyncWaitTimeoutMs(100);
        server.enqueue(json("""
                {"statuses":{"processed":[
                  {"id":"doc-target","file_path":"kb_1954071234567890100"}
                ]}}
                """));
        server.enqueue(json("{\"status\":\"deletion_started\"}"));
        for (int i = 0; i < 50; i++) {
            server.enqueue(json("{\"busy\":true,\"destructive_busy\":true}"));
        }

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.deleteByDoc("1954071234567890100"));
        assertTrue(e.getMessage().contains("doc-target"), "日志需带上远程文档 ID: " + e.getMessage());
    }

    @Test
    @DisplayName("删除进行中写入被 409 拒绝时，等删除完成后重试写入")
    void insertTextRetriesAfterDeletionInFlight() throws Exception {
        server.enqueue(new MockResponse.Builder().code(409).body("{\"detail\":\"busy\"}").build());
        server.enqueue(json("{\"busy\":true,\"destructive_busy\":true}"));
        server.enqueue(json("{\"busy\":false,\"destructive_busy\":false}"));
        server.enqueue(json("{\"status\":\"success\",\"message\":\"ok\",\"track_id\":\"t1\"}"));

        client.insertText("全文", "kb_1954071234567890100");

        assertEquals(List.of("/documents/text", "/documents/pipeline_status", "/documents/pipeline_status",
                "/documents/text"), takeTargets(4));
    }

    @Test
    @DisplayName("写入返回 409 且没有删除在进行（同名冲突）时抛出异常，不被静默吞掉")
    void insertTextFailsOnConflictWithoutDeletionInFlight() {
        server.enqueue(new MockResponse.Builder().code(409).body("{\"detail\":\"duplicate\"}").build());
        server.enqueue(json("{\"busy\":false,\"destructive_busy\":false}"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.insertText("全文", "kb_1954071234567890100"));
        assertTrue(e.getMessage().contains("409"), e.getMessage());
        assertTrue(e.getMessage().contains("kb_1954071234567890100"), e.getMessage());
    }

    @Test
    @DisplayName("写入返回 2xx 但业务状态为 failure 时抛出异常")
    void insertTextFailsOnBusinessFailure() {
        server.enqueue(json("{\"status\":\"failure\",\"message\":\"boom\",\"track_id\":\"t1\"}"));

        assertThrows(IllegalStateException.class, () -> client.insertText("全文", "kb_1954071234567890100"));
    }

    private List<String> takeTargets(int count) throws Exception {
        List<String> targets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request, "第 " + (i + 1) + " 个请求未发出，已收到: " + targets);
            targets.add(request.getTarget());
        }
        return targets;
    }

    private MockResponse json(String body) {
        return new MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private List<String> docIdsOf(RecordedRequest request) throws Exception {
        JsonNode body = objectMapper.readTree(request.getBody().utf8());
        List<String> ids = new ArrayList<>();
        body.path("doc_ids").forEach(node -> ids.add(node.asText()));
        return ids;
    }

    private static List<String> ids(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::getId).toList();
    }
}

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

package com.nageoffer.ai.ragent.mcp.config;

import com.nageoffer.ai.ragent.mcp.dao.mapper.OrderItemMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.OrderMapper;
import com.nageoffer.ai.ragent.mcp.executor.McpToolResults;
import com.nageoffer.ai.ragent.mcp.executor.bit.BitOrderQueryMcpExecutor;
import com.nageoffer.ai.ragent.mcp.executor.common.CurrentDateMcpExecutor;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringBootTest(classes = McpHttpProtocolTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
class McpHttpProtocolTest {

    @LocalServerPort
    private int port;

    @Autowired
    private OrderMapper orderMapper;

    @Test
    void shouldDiscoverAndCallToolsWithUserMetaOverHttp() {
        try (var client = McpClientBuilder.create("ragent-test")
                .streamableHttpTransport("http://127.0.0.1:" + port + "/mcp")
                .buildSync()) {
            client.initialize().block(Duration.ofSeconds(5));
            assertThat(client.listTools().block(Duration.ofSeconds(5)))
                    .extracting(tool -> tool.name())
                    .contains("current_date", "query_order");

            CallToolResult date = client.callTool("current_date", Map.of("timezone", "UTC"))
                    .block(Duration.ofSeconds(5));
            assertThat(date.isError()).isFalse();
            assertThat(((TextContent) date.content().get(0)).text()).contains("当前日期");

            CallToolResult missingIdentity = client.callTool("query_order", Map.of())
                    .block(Duration.ofSeconds(5));
            assertThat(missingIdentity.isError()).isTrue();
            assertThat(((TextContent) missingIdentity.content().get(0)).text()).contains("无法确认你的身份");

            CallToolResult order = client.callTool("query_order", Map.of("orderNo", "NO-1"),
                    Map.of(McpToolResults.META_USER_ID, "user-1"))
                    .block(Duration.ofSeconds(5));
            assertThat(order.isError()).isFalse();
            assertThat(((TextContent) order.content().get(0)).text()).contains("未找到订单 NO-1");
            verify(orderMapper).selectByNo("NO-1", "user-1");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import(McpServerConfig.class)
    static class Application {

        @Bean
        OrderMapper orderMapper() {
            return mock(OrderMapper.class);
        }

        @Bean
        OrderItemMapper orderItemMapper() {
            return mock(OrderItemMapper.class);
        }

        @Bean
        McpServerFeatures.SyncToolSpecification currentDateToolSpecification() {
            return new CurrentDateMcpExecutor().currentDateToolSpecification();
        }

        @Bean
        McpServerFeatures.SyncToolSpecification queryOrderToolSpecification(
                OrderMapper orderMapper, OrderItemMapper orderItemMapper) {
            return new BitOrderQueryMcpExecutor(orderMapper, orderItemMapper).queryOrderToolSpecification();
        }
    }
}

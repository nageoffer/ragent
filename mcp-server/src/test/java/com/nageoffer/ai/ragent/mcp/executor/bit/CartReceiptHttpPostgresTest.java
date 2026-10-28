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

package com.nageoffer.ai.ragent.mcp.executor.bit;

import com.nageoffer.ai.ragent.mcp.config.McpServerConfig;
import com.nageoffer.ai.ragent.mcp.dao.mapper.CartMapper;
import com.nageoffer.ai.ragent.mcp.executor.McpToolResults;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="cart.test.url",matches=".+")
@SpringBootTest(classes=CartReceiptHttpPostgresTest.Application.class,
        webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="server.address=127.0.0.1")
class CartReceiptHttpPostgresTest {
    static final String SCHEMA="cart_http_test_"+UUID.randomUUID().toString().replace("-","");
    static JdbcTemplate jdbc;
    @LocalServerPort int port;
    @AfterAll static void cleanup() { if(jdbc!=null) jdbc.execute("DROP SCHEMA "+SCHEMA+" CASCADE"); }

    @Test void metadataAndStructuredReceiptsRoundTripOverActualMcpHttp() {
        try(var client=McpClientBuilder.create("cart-http-test").streamableHttpTransport("http://127.0.0.1:"+port+"/mcp").buildSync()) {
            client.initialize().block(Duration.ofSeconds(5));
            assertThat(client.listTools().block(Duration.ofSeconds(5))).anySatisfy(t->{
                assertThat(t.name()).isEqualTo("query_cart_operation");
                assertThat(t.meta()).containsKey("com.nageoffer.ragent/cartReliabilityVersion");
            });
            var args=Map.<String,Object>of("skuCode","A","action","add","quantity",1);
            var identity=Map.<String,Object>of(McpToolResults.META_USER_ID,"u");
            assertThat(client.callTool("update_cart_item",args,identity).block(Duration.ofSeconds(5)).isError()).isTrue();
            UUID id=UUID.randomUUID();
            var meta=Map.<String,Object>of(McpToolResults.META_USER_ID,"u",CartReceiptService.META_OPERATION_ID,id.toString());
            for(int i=0;i<3;i++) {
                var result=client.callTool("add_cart_item_once",args,meta).block(Duration.ofSeconds(5));
                assertThat(result.isError()).isFalse();
                assertThat(((Map<?,?>)result.structuredContent()).get("status")).isEqualTo("SUCCEEDED");
            }
            var receipt=client.callTool("query_cart_operation",Map.of("operationId",id.toString()),identity).block(Duration.ofSeconds(5));
            assertThat(((Map<?,?>)receipt.structuredContent()).get("quantityAfter")).isEqualTo(1);
            assertThat(client.callTool("update_cart_item",Map.of("skuCode","A","action","remove"),meta)
                    .block(Duration.ofSeconds(5)).isError()).isTrue();
            assertThat(jdbc.queryForObject("SELECT quantity FROM t_cart WHERE user_id='u' AND sku_code='A'",Integer.class)).isEqualTo(1);
            var hidden=client.callTool("query_cart_operation",Map.of("operationId",id.toString()),
                    Map.of(McpToolResults.META_USER_ID,"other")).block(Duration.ofSeconds(5));
            assertThat(((Map<?,?>)hidden.structuredContent()).get("status")).isEqualTo("NOT_FOUND");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude=DataSourceAutoConfiguration.class)
    @Import(McpServerConfig.class)
    static class Application {
        @Bean DriverManagerDataSource dataSource() {
            var ds=CartReceiptPostgresTest.source(SCHEMA);jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE SCHEMA "+SCHEMA);
            new ResourceDatabasePopulator(new ClassPathResource("db/bit-schema.sql")).execute(ds);
            jdbc.update("INSERT INTO t_product_sku(id,sku_code,spu_code,name,specs,price,stock,status,create_time,update_time) VALUES(1,'A','S','商品','{}',10,100,'在售',now(),now())");
            return ds;
        }
        @Bean CartReceiptService receiptService(DriverManagerDataSource ds) throws Exception { return CartReceiptPostgresTest.service(ds); }
        @Bean io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification addTool(BitCartUpdateMcpExecutor executor) {
            return executor.updateCartItemToolSpecification();
        }
        @Bean io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification reliableAddTool(BitCartUpdateMcpExecutor executor) {
            return executor.addCartItemOnceToolSpecification();
        }
        @Bean io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification queryTool(BitCartUpdateMcpExecutor executor) {
            return executor.queryCartOperationToolSpecification();
        }
        @Bean BitCartUpdateMcpExecutor executor(DriverManagerDataSource ds,CartReceiptService service) throws Exception {
            var config=new MybatisConfiguration(); config.addMapper(CartMapper.class);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
            return new BitCartUpdateMcpExecutor(new SqlSessionTemplate(factory.getObject()).getMapper(CartMapper.class),service);
        }
    }
}

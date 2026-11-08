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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.nageoffer.ai.ragent.mcp.dao.mapper.CartMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.ProductSkuMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** 使用独立 schema，不访问演示购物车。通过 -Dcart.test.url 启用。 */
@EnabledIfSystemProperty(named = "cart.test.url", matches = ".+")
class CartReceiptPostgresTest {
    String schema;
    DriverManagerDataSource ds;
    JdbcTemplate jdbc;
    CartReceiptService service;

    static DriverManagerDataSource source(String schema) {
        String url = System.getProperty("cart.test.url");
        return new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getProperty("cart.test.user", "postgres"), System.getProperty("cart.test.password", "postgres"));
    }

    static CartReceiptService service(DriverManagerDataSource ds) throws Exception {
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(CartMapper.class);
        config.addMapper(ProductSkuMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        var target = new CartReceiptService(new JdbcTemplate(ds), session.getMapper(CartMapper.class), session.getMapper(ProductSkuMapper.class));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        return (CartReceiptService) proxy.getProxy();
    }

    @BeforeEach void setup() throws Exception {
        schema = "cart_test_" + UUID.randomUUID().toString().replace("-", "");
        ds = source(schema);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA " + schema);
        new ResourceDatabasePopulator(new ClassPathResource("db/bit-schema.sql")).execute(ds);
        jdbc.update("""
                INSERT INTO t_product_sku(id,sku_code,spu_code,name,specs,price,stock,status,create_time,update_time)
                VALUES(1,'A','SPU','测试商品','{}',10,100,'在售',now(),now())
                """);
        service = service(ds);
    }
    @AfterEach void cleanup() { if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }
    Map<?,?> result(UUID id) { return (Map<?,?>) service.query(id,"u").structuredContent(); }
    int quantity() { return jdbc.queryForObject("SELECT COALESCE(sum(quantity),0) FROM t_cart WHERE user_id='u' AND sku_code='A'", Integer.class); }

    @Test void concurrentDuplicatesAndLaterNewOperation() throws Exception {
        UUID id = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            var barrier = new CountDownLatch(1);
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int i=0;i<20;i++) jobs.add(pool.submit(() -> { barrier.await(); service.add(id,"u","A",2); return null; }));
            barrier.countDown();
            for (var job : jobs) job.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(quantity()).isEqualTo(2);
        assertThat(result(id).get("status")).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_cart_operation_receipt", Integer.class)).isEqualTo(1);
        for (int i=0;i<10;i++) service.add(id,"u","A",2);
        assertThat(quantity()).isEqualTo(2);
        service.add(UUID.randomUUID(),"u","A",1);
        assertThat(quantity()).isEqualTo(3);
        // 当前数量再次变化，原回执中的历史数量不变。
        assertThat(result(id).get("quantityAfter")).isEqualTo(2);
    }

    @Test void delayedOriginalArrivesAfterRetry() throws Exception {
        UUID id=UUID.randomUUID();
        var release=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var original=pool.submit(()->{ release.await(); return service.add(id,"u","A",1); });
            service.add(id,"u","A",1);
            release.countDown();
            assertThat(((Map<?,?>)original.get(10,TimeUnit.SECONDS).structuredContent()).get("status")).isEqualTo("SUCCEEDED");
            assertThat(quantity()).isEqualTo(1);
        } finally { release.countDown();pool.shutdownNow(); }
    }

    @Test void conflictingParametersAndOwnerCannotOverwriteReceipt() {
        UUID id = UUID.randomUUID();
        service.add(id,"u","A",1);
        assertThat(service.add(id,"u","A",2).isError()).isTrue();
        assertThat(service.add(id,"other","A",1).isError()).isTrue();
        assertThat(service.add(id,"u","B",1).isError()).isTrue();
        assertThat(((Map<?,?>) service.query(id,"other").structuredContent()).get("status")).isEqualTo("NOT_FOUND");
        assertThat(quantity()).isEqualTo(1);
        assertThat(result(id).get("quantity")).isEqualTo(1);
    }

    @Test void fixedRejectionAndHistoricalSuccessSurviveProductChange() {
        UUID denied = UUID.randomUUID();
        jdbc.update("UPDATE t_product_sku SET status='下架'");
        service.add(denied,"u","A",1);
        jdbc.update("UPDATE t_product_sku SET status='在售'");
        service.add(denied,"u","A",1);
        assertThat(result(denied).get("status")).isEqualTo("REJECTED");
        assertThat(quantity()).isZero();
        UUID success = UUID.randomUUID();
        service.add(success,"u","A",1);
        jdbc.update("UPDATE t_product_sku SET status='下架'");
        service.add(success,"u","A",1);
        assertThat(result(success).get("status")).isEqualTo("SUCCEEDED");
        assertThat(quantity()).isEqualTo(1);
    }

    @Test void quantityLimitAndUnknownLookup() {
        UUID missing = UUID.randomUUID();
        assertThat(result(missing).get("status")).isEqualTo("NOT_FOUND");
        service.add(UUID.randomUUID(),"u","A",98);
        UUID denied = UUID.randomUUID();
        service.add(denied,"u","A",2);
        assertThat(result(denied).get("status")).isEqualTo("REJECTED");
        assertThat(quantity()).isEqualTo(98);
    }

    @Test void receiptWriteFailureRollsBackCartChange() {
        jdbc.execute("ALTER TABLE t_cart_operation_receipt ADD CONSTRAINT test_reject_success CHECK(result_status<>'SUCCEEDED')");
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.add(id,"u","A",1)).isInstanceOf(RuntimeException.class);
        assertThat(quantity()).isZero();
        assertThat(result(id).get("status")).isEqualTo("NOT_FOUND");
    }

    @Test void versionSevenUpgradePreservesCartAndAddsReceiptTable() {
        // 模拟升级前已经存在的业务库：迁移只增加回执表和版本记录。
        jdbc.execute("DROP TABLE t_cart_operation_receipt");
        jdbc.execute("CREATE TABLE t_schema_version(version integer PRIMARY KEY,applied_at timestamptz DEFAULT now())");
        jdbc.update("INSERT INTO t_schema_version(version) VALUES(7)");
        jdbc.update("INSERT INTO t_cart(id,user_id,sku_code,quantity,added_price,create_time,update_time) VALUES(1,'u','A',2,10,now(),now())");
        var properties=new com.nageoffer.ai.ragent.mcp.config.bit.BitProperties.Datasource();
        properties.setUrl(ds.getUrl());properties.setUsername(ds.getUsername());properties.setPassword(ds.getPassword());
        new com.nageoffer.ai.ragent.mcp.config.bit.BitSchemaInitializer(properties).initialize();
        assertThat(quantity()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT max(version) FROM t_schema_version",Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_cart_operation_receipt",Integer.class)).isZero();
    }

    @Test void forcedProcessExitBeforeAndAfterCommit() throws Exception {
        UUID before = UUID.randomUUID();
        crashWorker(before, "before");
        assertThat(quantity()).isZero();
        assertThat(result(before).get("status")).isEqualTo("NOT_FOUND");
        UUID after = UUID.randomUUID();
        crashWorker(after, "after");
        // 加购已提交但响应尚未交给调用方；重建服务模拟恢复后查询，再重放。
        service = service(ds);
        assertThat(result(after).get("status")).isEqualTo("SUCCEEDED");
        service.add(after,"u","A",1);
        assertThat(quantity()).isEqualTo(1);
    }

    private void crashWorker(UUID id, String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"),"bin","java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "-Dcart.test.url="+System.getProperty("cart.test.url"),
                "-Dcart.test.user="+System.getProperty("cart.test.user","postgres"),
                "-Dcart.test.password="+System.getProperty("cart.test.password","postgres"),
                Worker.class.getName(), schema, id.toString(), mode).redirectErrorStream(true).start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> ready = reader.submit(() -> {
                var lines = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = lines.readLine()) != null) if (line.equals("READY_TO_KILL")) return true;
                return false;
            });
            assertThat(ready.get(20,TimeUnit.SECONDS)).isTrue();
        } finally {
            process.destroyForcibly();
            assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();
            reader.shutdownNow();
        }
    }

    public static class Worker {
        public static void main(String[] args) throws Exception {
            var ds = source(args[0]);
            var service = service(ds);
            UUID id = UUID.fromString(args[1]);
            Runnable pause = () -> {
                System.out.println("READY_TO_KILL"); System.out.flush();
                try { Thread.sleep(60000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            };
            if ("before".equals(args[2])) new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(s -> {
                service.add(id,"u","A",1); pause.run();
            });
            else { service.add(id,"u","A",1); pause.run(); }
        }
    }
}

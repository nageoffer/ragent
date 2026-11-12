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

package com.nageoffer.ai.ragent.mcp.config.bit;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.nageoffer.ai.ragent.mcp.dao.handler.BitMetaObjectHandler;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * 比特严选业务库的数据源
 * <p>
 * 启动时先初始化业务库表结构，再创建连接池
 */
@Configuration
@EnableConfigurationProperties(BitProperties.class)
@MapperScan("com.nageoffer.ai.ragent.mcp.dao.mapper")
public class BitDataSourceConfig {

    /**
     * 建库建表在这里触发，而不是找个 @PostConstruct 挂着
     * <p>
     * 建库那一步得先连维护库，连接池却是冲着目标库去的——顺序反了就是「库还没建就先连」。
     * 写在 bean 方法里，先后关系由调用顺序保证，不依赖任何初始化回调的相对次序
     */
    @Bean
    public DataSource bitDataSource(BitProperties properties) {
        new BitSchemaInitializer(properties.getDatasource()).initialize();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("bit-pool");
        dataSource.setJdbcUrl(properties.getDatasource().getUrl());
        dataSource.setUsername(properties.getDatasource().getUsername());
        dataSource.setPassword(properties.getDatasource().getPassword());
        dataSource.setMaximumPoolSize(8);
        return dataSource;
    }

    /**
     * SqlSessionFactory 自己装而不是靠 MP 的自动配置
     * <p>
     * 自动配置认 spring.datasource，而本进程的数据源挂在 ragent.bit.datasource 下、
     * 并由本配置负责先初始化库结构再创建连接池
     */
    @Bean
    public SqlSessionFactory bitSqlSessionFactory(DataSource bitDataSource) throws Exception {
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setMetaObjectHandler(new BitMetaObjectHandler());

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(bitDataSource);
        factory.setConfiguration(new MybatisConfiguration());
        factory.setGlobalConfig(globalConfig);
        return factory.getObject();
    }

    @Bean
    public SqlSessionTemplate bitSqlSessionTemplate(SqlSessionFactory bitSqlSessionFactory) {
        return new SqlSessionTemplate(bitSqlSessionFactory);
    }

    @Bean
    public PlatformTransactionManager bitTransactionManager(DataSource bitDataSource) {
        return new DataSourceTransactionManager(bitDataSource);
    }

    /**
     * 写工具用编程式事务而不是 @Transactional
     * <p>
     * 那些 executor 自己带 @Bean 方法，加事务注解会让它们被代理一层；
     * 何况在教学项目里，事务边界从哪行开到哪行，看得见比省几行强
     */
    @Bean
    public TransactionTemplate bitTransactionTemplate(PlatformTransactionManager bitTransactionManager) {
        return new TransactionTemplate(bitTransactionManager);
    }
}

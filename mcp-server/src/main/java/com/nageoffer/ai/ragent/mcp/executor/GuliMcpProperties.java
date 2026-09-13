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

package com.nageoffer.ai.ragent.mcp.executor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 谷粒商城 MCP 工具配置属性
 * 
 * 配置前缀：guli
 * 支持配置项：
 *   - guli.product.base-url: 商品服务基础 URL（默认：http://localhost:8080/product）
 *   - guli.ware.base-url: 仓储服务基础 URL（默认：http://localhost:8080/ware）
 */
@Data
@Component
@ConfigurationProperties(prefix = "guli")
public class GuliMcpProperties {

    /**
     * 商品服务配置
     */
    private ProductServiceConfig product = new ProductServiceConfig();

    /**
     * 仓储服务配置
     */
    private WareServiceConfig ware = new WareServiceConfig();

    @Data
    public static class ProductServiceConfig {
        /**
         * 商品服务基础 URL
         */
        private String baseUrl = "http://localhost:8080/product";
    }

    @Data
    public static class WareServiceConfig {
        /**
         * 仓储服务基础 URL
         */
        private String baseUrl = "http://localhost:8080/ware";
    }
}

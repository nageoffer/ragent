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

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具执行器：商品详情查询（真实 HTTP 调用谷粒商城接口）
 * 
 * 调用谷粒商城商品详情接口：GET /product/display/item/{skuId}
 * 返回：PmsSkuItemVo 包含 SKU 信息、图片、销售属性等
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuliProductDetailMcpExecutor {

    private static final String TOOL_ID = "product_detail_query";
    
    /**
     * 谷粒商城商品服务基础 URL
     * 配置方式：application.yml 中配置 guli.product.base-url
     * 默认值：http://localhost:8080/product
     */
    private final GuliMcpProperties guliMcpProperties;
    
    private final RestTemplateBuilder restTemplateBuilder;

    @Bean
    public McpServerFeatures.SyncToolSpecification productDetailToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(buildTool(),
                (exchange, request) -> handleCall(request));
    }

    private Tool buildTool() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("skuId", Map.of(
                "type", "integer",
                "description", "SKU ID，例如：1, 2, 3 等。用于查询具体规格的商品详情"
        ));

        JsonSchema inputSchema = new JsonSchema(
                "object", properties, List.of("skuId"), null, null, null);

        return Tool.builder()
                .name(TOOL_ID)
                .description("查询商品详细信息，包括商品名称、品牌、分类、描述、价格、图片、销售属性等。需要提供 SKU ID。")
                .inputSchema(inputSchema)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
            Long skuId = longArg(args, "skuId");

            if (skuId == null || skuId <= 0) {
                return errorResult("请提供有效的 SKU ID");
            }

            // 调用谷粒商城商品详情接口
            String url = guliMcpProperties.getProduct().getBaseUrl() + "/display/item/" + skuId;
            log.info("调用谷粒商城商品详情接口：{}", url);
            
            RestTemplate restTemplate = restTemplateBuilder
                    .setConnectTimeout(Duration.ofSeconds(5))
                    .setReadTimeout(Duration.ofSeconds(10))
                    .build();
            
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            
            if (response.getStatusCodeValue() == 200 && response.getBody() != null) {
                Map<String, Object> resultData = response.getBody();
                String result = buildProductDetailResult(skuId, resultData);
                
                log.info("MCP 工具调用完成，toolId={}, skuId={}, elapsed={}ms",
                        TOOL_ID, skuId, System.currentTimeMillis() - startMs);
                return successResult(result);
            } else {
                return errorResult("商品详情查询失败，HTTP 状态码：" + response.getStatusCodeValue());
            }
        } catch (Exception e) {
            log.error("MCP 工具调用失败，toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return errorResult("查询失败：" + e.getMessage());
        }
    }

    private String buildProductDetailResult(Long skuId, Map<String, Object> responseData) {
        StringBuilder sb = new StringBuilder();
        sb.append("【商品详情】\n\n");
        
        // 处理谷粒商城返回的数据结构
        // 通常返回格式：{code: xxx, msg: xxx, data: {...}}
        Object data = responseData.get("data");
        if (data instanceof Map) {
            Map<String, Object> productData = (Map<String, Object>) data;
            
            sb.append(String.format("SKU ID: %d\n", skuId));
            sb.append(String.format("商品名称：%s\n", getField(productData, "skuName")));
            sb.append(String.format("商品标题：%s\n", getField(productData, "spuName")));
            sb.append(String.format("品牌：%s\n", getField(productData, "brandName")));
            sb.append(String.format("分类：%s\n", getField(productData, "categoryName")));
            sb.append(String.format("价格：￥%s\n", getField(productData, "price")));
            sb.append(String.format("描述：%s\n", getField(productData, "saleComment")));
            
            // 处理图片列表
            Object images = productData.get("images");
            if (images instanceof List) {
                sb.append(String.format("图片数量：%d 张\n", ((List<?>) images).size()));
            }
            
            // 处理销售属性
            Object attrs = productData.get("saleAttrs");
            if (attrs instanceof List) {
                sb.append(String.format("销售属性：%d 个\n", ((List<?>) attrs).size()));
            }
            
            sb.append("\n如需查询库存详情，请使用库存查询工具。");
        } else {
            sb.append(String.format("SKU ID: %d\n", skuId));
            sb.append("商品信息：").append(responseData.toString());
        }
        
        return sb.toString().trim();
    }

    private String getField(Map<String, Object> data, String fieldName) {
        Object value = data.get(fieldName);
        return value != null ? value.toString() : "未知";
    }

    private static Long longArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static CallToolResult successResult(String text) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(text)))
                .isError(false)
                .build();
    }

    private static CallToolResult errorResult(String message) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(message)))
                .isError(true)
                .build();
    }
}

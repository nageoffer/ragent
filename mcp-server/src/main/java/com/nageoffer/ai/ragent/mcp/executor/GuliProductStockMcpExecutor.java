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
 * MCP 工具执行器：商品库存查询（真实 HTTP 调用谷粒商城接口）
 * 
 * 调用谷粒商城库存查询接口：GET /ware/wareSku/list?skuId={skuId}&wareId={wareId}
 * 返回：TableDataInfo<WmsWareSkuVo> 包含库存数、锁定库存等
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuliProductStockMcpExecutor {

    private static final String TOOL_ID = "product_stock_query";
    
    /**
     * 谷粒商城仓储服务基础 URL
     * 配置方式：application.yml 中配置 guli.ware.base-url
     * 默认值：http://localhost:8080/ware
     */
    private final GuliMcpProperties guliMcpProperties;
    
    private final RestTemplateBuilder restTemplateBuilder;

    @Bean
    public McpServerFeatures.SyncToolSpecification productStockToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(buildTool(),
                (exchange, request) -> handleCall(request));
    }

    private Tool buildTool() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("skuId", Map.of(
                "type", "integer",
                "description", "SKU ID，例如：1, 2, 3 等。用于查询具体规格的库存"
        ));

        properties.put("wareId", Map.of(
                "type", "integer",
                "description", "仓库 ID（可选），如果不提供则查询所有仓库的库存汇总"
        ));

        JsonSchema inputSchema = new JsonSchema(
                "object", properties, List.of("skuId"), null, null, null);

        return Tool.builder()
                .name(TOOL_ID)
                .description("查询商品库存信息，支持按 SKU ID 和仓库 ID 查询。返回库存数量、锁定库存、可用库存等详细信息。")
                .inputSchema(inputSchema)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
            Long skuId = longArg(args, "skuId");
            Long wareId = longArg(args, "wareId");

            if (skuId == null || skuId <= 0) {
                return errorResult("请提供有效的 SKU ID");
            }

            // 构建查询参数
            StringBuilder urlBuilder = new StringBuilder(guliMcpProperties.getWare().getBaseUrl() + "/wareSku/list?skuId=" + skuId);
            if (wareId != null && wareId > 0) {
                urlBuilder.append("&wareId=").append(wareId);
            }
            
            String url = urlBuilder.toString();
            log.info("调用谷粒商城库存查询接口：{}", url);
            
            RestTemplate restTemplate = restTemplateBuilder
                    .setConnectTimeout(Duration.ofSeconds(5))
                    .setReadTimeout(Duration.ofSeconds(10))
                    .build();
            
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            
            if (response.getStatusCodeValue() == 200 && response.getBody() != null) {
                Map<String, Object> resultData = response.getBody();
                String result = buildStockResult(skuId, wareId, resultData);
                
                log.info("MCP 工具调用完成，toolId={}, skuId={}, wareId={}, elapsed={}ms",
                        TOOL_ID, skuId, wareId, System.currentTimeMillis() - startMs);
                return successResult(result);
            } else {
                return errorResult("库存查询失败，HTTP 状态码：" + response.getStatusCodeValue());
            }
        } catch (Exception e) {
            log.error("MCP 工具调用失败，toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return errorResult("查询失败：" + e.getMessage());
        }
    }

    private String buildStockResult(Long skuId, Long wareId, Map<String, Object> responseData) {
        StringBuilder sb = new StringBuilder();
        sb.append("【商品库存详情】\n\n");
        
        // 处理谷粒商城返回的数据结构
        // 通常返回格式：{code: xxx, msg: xxx, data: {...}}
        // data 可能包含 rows 列表或单个对象
        Object data = responseData.get("data");
        if (data instanceof Map) {
            Map<String, Object> stockData = (Map<String, Object>) data;
            
            // 尝试获取库存列表（TableDataInfo 格式）
            Object rows = stockData.get("rows");
            if (rows instanceof List && !((List<?>) rows).isEmpty()) {
                List<Map<String, Object>> stockList = (List<Map<String, Object>>) rows;
                sb.append(String.format("SKU ID: %d\n", skuId));
                if (wareId != null) {
                    sb.append(String.format("仓库 ID: %d\n", wareId));
                }
                sb.append(String.format("查询结果：%d 条记录\n\n", stockList.size()));
                
                int totalStock = 0;
                int totalLocked = 0;
                
                for (Map<String, Object> stockItem : stockList) {
                    sb.append("---\n");
                    sb.append(String.format("仓库 ID: %s\n", getField(stockItem, "wareId")));
                    sb.append(String.format("仓库名称：%s\n", getField(stockItem, "wareName")));
                    
                    Integer stock = getIntField(stockItem, "stock");
                    Integer locked = getIntField(stockItem, "stockLocked");
                    Integer available = stock != null && locked != null ? stock - locked : stock;
                    
                    sb.append(String.format("总库存：%d 件\n", stock != null ? stock : 0));
                    sb.append(String.format("锁定库存：%d 件\n", locked != null ? locked : 0));
                    sb.append(String.format("可用库存：%d 件\n", available != null ? available : 0));
                    
                    if (stock != null) totalStock += stock;
                    if (locked != null) totalLocked += locked;
                }
                
                sb.append("\n---\n");
                sb.append(String.format("汇总：总库存 %d 件，锁定 %d 件，可用 %d 件\n", 
                        totalStock, totalLocked, totalStock - totalLocked));
                
                // 库存状态提示
                int availableTotal = totalStock - totalLocked;
                if (availableTotal <= 0) {
                    sb.append("\n⚠️ 提示：该商品已缺货！");
                } else if (availableTotal <= 10) {
                    sb.append("\n🔴 提示：库存紧张，请尽快下单！");
                } else if (availableTotal <= 30) {
                    sb.append("\n🟡 提示：库存较少，建议尽早购买。");
                } else {
                    sb.append("\n🟢 库存充足，可放心购买。");
                }
                
            } else {
                // 单个库存对象或空数据
                sb.append(String.format("SKU ID: %d\n", skuId));
                if (wareId != null) {
                    sb.append(String.format("仓库 ID: %d\n", wareId));
                }
                
                Integer stock = getIntField(stockData, "stock");
                Integer locked = getIntField(stockData, "stockLocked");
                
                if (stock != null) {
                    sb.append(String.format("总库存：%d 件\n", stock));
                    sb.append(String.format("锁定库存：%d 件\n", locked != null ? locked : 0));
                    sb.append(String.format("可用库存：%d 件\n", stock - (locked != null ? locked : 0)));
                    
                    int available = stock - (locked != null ? locked : 0);
                    if (available <= 0) {
                        sb.append("\n⚠️ 提示：该商品已缺货！");
                    } else if (available <= 10) {
                        sb.append("\n🔴 提示：库存紧张，请尽快下单！");
                    } else if (available <= 30) {
                        sb.append("\n🟡 提示：库存较少，建议尽早购买。");
                    } else {
                        sb.append("\n🟢 库存充足，可放心购买。");
                    }
                } else {
                    sb.append("库存信息：暂无数据\n");
                    sb.append("原始数据：").append(stockData.toString());
                }
            }
        } else {
            sb.append(String.format("SKU ID: %d\n", skuId));
            sb.append("库存信息：").append(responseData.toString());
        }
        
        return sb.toString().trim();
    }

    private String getField(Map<String, Object> data, String fieldName) {
        Object value = data.get(fieldName);
        return value != null ? value.toString() : "未知";
    }

    private Integer getIntField(Map<String, Object> data, String fieldName) {
        Object value = data.get(fieldName);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
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

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

import com.nageoffer.ai.ragent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.ragent.mcp.dao.mapper.CartMapper;
import com.nageoffer.ai.ragent.mcp.dao.result.CartLineResult;
import com.nageoffer.ai.ragent.mcp.executor.McpToolResults;
import com.nageoffer.ai.ragent.mcp.executor.McpToolSchema;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;



/**
 * 购物车查询
 * <p>
 * 加购价与现价两列都给：降价了、涨价了、缺货了都得在结算前先说清楚
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitCartQueryMcpExecutor {

    private static final String TOOL_ID = "query_cart";

    private final CartMapper cartMapper;

    @Bean
    public McpServerFeatures.SyncToolSpecification queryCartToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .build();

        return Tool.builder()
                .name(TOOL_ID)
                .description("查询当前登录用户的购物车，返回每件商品的型号、数量、加购价、现价、价差、库存情况，"
                        + "以及按品类的小计和合计金额。用户问「购物车里有什么」「一共多少钱」「能用哪张券」时先调它，"
                        + "拿到的合计金额和品类再去查可用优惠券，不要让用户自己报金额")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.READ_ONLY)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        String userId = McpToolResults.userId(request);
        if (userId == null) {
            return McpToolResults.identityRequired(TOOL_ID);
        }
        try {
            List<CartLineResult> lines = cartMapper.selectLines(userId);

            log.info("MCP 工具调用完成, toolId={}, lines={}, elapsed={}ms",
                    TOOL_ID, lines.size(), System.currentTimeMillis() - startMs);
            return McpToolResults.success(buildResult(lines));
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("购物车查询", e);
        }
    }

    private String buildResult(List<CartLineResult> lines) {
        if (lines.isEmpty()) {
            return "购物车是空的";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【购物车 %d 件商品】%n", lines.size()));

        // 品类小计单独给一份：品类券能不能用要按品类金额判，让模型自己加容易错
        Map<String, BigDecimal> categoryAmount = new TreeMap<>();
        BigDecimal total = BigDecimal.ZERO;
        int unavailable = 0;

        for (int i = 0; i < lines.size(); i++) {
            CartLineResult line = lines.get(i);
            BigDecimal subtotal = line.getPrice().multiply(BigDecimal.valueOf(line.getQuantity()));
            total = total.add(subtotal);
            categoryAmount.merge(line.getCategory(), subtotal, BigDecimal::add);

            String stockLabel = BitToolSupport.stockLabel(line.getStatus(), line.getStock());
            boolean buyable = "在售".equals(line.getStatus()) && line.getStock() >= line.getQuantity();
            if (!buyable) {
                unavailable++;
            }

            sb.append(String.format("%n%d. %s（%s）×%d%n", i + 1, line.getSkuName(), line.getSkuCode(), line.getQuantity()));
            sb.append(String.format("   品类: %s | 现价: %s | 小计: %s%n",
                    line.getCategory(), BitToolSupport.money(line.getPrice()), BitToolSupport.money(subtotal)));
            sb.append(String.format("   加购价: %s%s%n",
                    BitToolSupport.money(line.getAddedPrice()), priceDiff(line)));
            sb.append(String.format("   库存: %s%s%n", stockLabel, buyable ? "" : "（当前不可结算）"));
            sb.append(String.format("   加购时间: %s%n", BitToolSupport.dateTime(line.getCreateTime())));
        }

        sb.append(String.format("%n合计（按现价）: %s%n", BitToolSupport.money(total)));
        // 排版成 `手机:3999.00,耳机:1200.00`，优惠券试算的 categoryAmounts 可以整行照抄
        List<String> parts = categoryAmount.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + BitToolSupport.money(entry.getValue()))
                .toList();
        sb.append(String.format("品类小计: %s%n", String.join(",", parts)));
        if (unavailable > 0) {
            sb.append(String.format("提示: 有 %d 件商品当前不可结算，下单前需要先移除或改数量%n", unavailable));
        }
        return sb.toString().trim();
    }

    private String priceDiff(CartLineResult line) {
        if (line.getAddedPrice() == null) {
            return "";
        }
        int compared = line.getPrice().compareTo(line.getAddedPrice());
        if (compared == 0) {
            return "（价格未变）";
        }
        BigDecimal diff = line.getPrice().subtract(line.getAddedPrice()).abs();
        return compared < 0
                ? String.format("（已降价 %s）", BitToolSupport.money(diff))
                : String.format("（已涨价 %s）", BitToolSupport.money(diff));
    }

}

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

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.mcp.dao.entity.CartDO;
import com.nageoffer.ai.ragent.mcp.dao.entity.ProductSkuDO;
import com.nageoffer.ai.ragent.mcp.dao.mapper.CartMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.ProductSkuMapper;
import com.nageoffer.ai.ragent.mcp.config.McpToolAnnotations;
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

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.integer;

/**
 * 购物车操作：新增、减少或移除商品
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitCartUpdateMcpExecutor {

    private static final String TOOL_ID = "update_cart_item";
    private static final int MAX_QUANTITY = 99;

    private final CartMapper cartMapper;
    private final ProductSkuMapper productSkuMapper;

    @Bean
    public McpServerFeatures.SyncToolSpecification updateCartItemToolSpecification() {
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("skuCode", "商品 SKU 编码，来自商品查询或购物车查询").title("商品型号"))
                .required(string("action", "add 新增，decrease 减少，remove 移除")
                        .options(List.of("add", "decrease", "remove")).title("操作"))
                .optional(integer("quantity", "新增或减少时必填，表示本次增减件数，须为 1 到 99 的整数；移除时不用填")
                        .title("增减数量"))
                .build();
        Tool tool = Tool.builder()
                .name(TOOL_ID)
                .description("修改当前登录用户的购物车。add：没有则新增，已有则累加，总数最多 99，超限不修改；"
                        + "decrease：减少指定件数，最低保留 1 件；remove：直接移除商品。"
                        + "新增、减少的 quantity 是本次变化量，不是最终总数。"
                        + "增减结果不确定时先查购物车，不要直接重试。已下架商品不能新增")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.WRITE)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        String userId = McpToolResults.userId(request);
        if (userId == null) {
            return McpToolResults.identityRequired(TOOL_ID);
        }
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String skuCode = StrUtil.trimToNull(MapUtil.getStr(args, "skuCode"));
            String action = MapUtil.getStr(args, "action", "");
            if (skuCode == null) {
                return BitToolSupport.rejected("请先确认商品编码");
            }
            if (!List.of("add", "decrease", "remove").contains(action)) {
                return BitToolSupport.rejected("操作必须是 add、decrease 或 remove");
            }
            if ("remove".equals(action)) {
                cartMapper.delete(Wrappers.<CartDO>lambdaQuery()
                        .eq(CartDO::getUserId, userId).eq(CartDO::getSkuCode, skuCode));
                return McpToolResults.success("已移除商品，购物车中不再包含 " + skuCode);
            }
            Object value = args.get("quantity");
            if (!(value instanceof Number number)) {
                return BitToolSupport.rejected("增减数量必须是 1 到 " + MAX_QUANTITY + " 之间的整数");
            }
            int quantity;
            try {
                quantity = new BigDecimal(number.toString()).intValueExact();
            } catch (NumberFormatException | ArithmeticException e) {
                return BitToolSupport.rejected("增减数量必须是 1 到 " + MAX_QUANTITY + " 之间的整数");
            }
            if (quantity < 1 || quantity > MAX_QUANTITY) {
                return BitToolSupport.rejected("增减数量必须是 1 到 " + MAX_QUANTITY + " 之间的整数");
            }
            if ("decrease".equals(action)) {
                Integer total = cartMapper.decreaseQuantity(userId, skuCode, quantity);
                return total == null
                        ? BitToolSupport.rejected("购物车中没有 " + skuCode + "，无法减少")
                        : McpToolResults.success(String.format("已调整 %s 的数量，车内共 %d 件（最低保留 1 件）", skuCode, total));
            }
            ProductSkuDO product = productSkuMapper.selectOne(Wrappers.<ProductSkuDO>lambdaQuery()
                    .eq(ProductSkuDO::getSkuCode, skuCode));
            if (product == null || !"在售".equals(product.getStatus())) {
                return BitToolSupport.rejected("商品不存在或已下架，无法加入购物车：" + skuCode);
            }
            Integer total = cartMapper.addQuantity(userId, skuCode, quantity, product.getPrice(), MAX_QUANTITY);
            if (total == null) {
                return BitToolSupport.rejected("加入后同款商品将超过 " + MAX_QUANTITY + " 件，本次未加入");
            }
            String result = String.format("已加入购物车: %s（%s），本次新增 %d 件，车内共 %d 件%n单价 %s，小计 %s",
                    product.getName(), skuCode, quantity, total, BitToolSupport.money(product.getPrice()),
                    BitToolSupport.money(product.getPrice().multiply(BigDecimal.valueOf(total))));
            if (product.getStock() < total) {
                result += String.format("%n提示: 当前库存 %d 件，下单时会重新检查库存", product.getStock());
            }
            return McpToolResults.success(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}", TOOL_ID, e);
            return McpToolResults.failure("购物车调整", e);
        }
    }
}

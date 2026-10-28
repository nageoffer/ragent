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
import com.nageoffer.ai.ragent.mcp.dao.mapper.CartMapper;
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
    private final CartReceiptService cartReceiptService;

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
                        + "加购结果不确定时按操作编号查询回执，不要根据当前数量推断。已下架商品不能新增")
                .inputSchema(inputSchema)
                .meta(Map.of("com.nageoffer.ragent/cartReliabilityVersion", 1))
                .annotations(McpToolAnnotations.WRITE)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    /** 专用协议入口：旧版 MCP 没有此工具，回滚时也不会误走无去重的 add。 */
    @Bean
    public McpServerFeatures.SyncToolSpecification addCartItemOnceToolSpecification() {
        Tool tool = Tool.builder().name("add_cart_item_once")
                .description("可靠加购内部入口，必须携带系统操作编号，仅允许 add")
                .inputSchema(McpToolSchema.object().required(string("skuCode", "商品 SKU"))
                        .required(string("action", "固定 add").options(List.of("add")))
                        .required(integer("quantity", "本次增加量，1 到 99")).build())
                .meta(Map.of("com.nageoffer.ragent/cartReliabilityVersion", 1))
                .annotations(McpToolAnnotations.WRITE).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            if (!"add".equals(McpToolResults.args(request).get("action")))
                return McpToolResults.error("可靠加购入口仅允许 add");
            return handleCall(request);
        }).build();
    }

    @Bean
    public McpServerFeatures.SyncToolSpecification queryCartOperationToolSpecification() {
        Tool tool = Tool.builder().name(CartReceiptService.QUERY_TOOL)
                .description("按操作编号只读核实一次加购结果；未找到不代表未执行")
                .inputSchema(McpToolSchema.object().required(string("operationId", "业务操作编号")).build())
                .meta(Map.of("com.nageoffer.ragent/cartReliabilityVersion", 1))
                .annotations(McpToolAnnotations.READ_ONLY).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            String userId = McpToolResults.userId(request);
            if (userId == null) return McpToolResults.identityRequired(CartReceiptService.QUERY_TOOL);
            try {
                return cartReceiptService.query(java.util.UUID.fromString(
                        MapUtil.getStr(McpToolResults.args(request), "operationId")), userId);
            } catch (Exception e) {
                log.warn("加购回执查询异常", e);
                return McpToolResults.error("暂时无法查询加购回执，结果待核实");
            }
        }).build();
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
            if (request.meta() != null && request.meta().containsKey(CartReceiptService.META_OPERATION_ID)
                    && !"add".equals(action)) return McpToolResults.error("操作编号绑定 add，不能替换动作");
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
            Object operationId = request.meta() == null ? null : request.meta().get(CartReceiptService.META_OPERATION_ID);
            if (!(operationId instanceof String id)) {
                return McpToolResults.error("缺少可靠加购操作编号，本次未执行");
            }
            return cartReceiptService.add(java.util.UUID.fromString(id), userId, skuCode, quantity);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}", TOOL_ID, e);
            return McpToolResults.error("购物车处理异常，结果尚未确定；加购请按操作编号核实");
        }
    }
}

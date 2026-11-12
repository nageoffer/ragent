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
import com.nageoffer.ai.ragent.mcp.dao.entity.OrderDO;
import com.nageoffer.ai.ragent.mcp.dao.mapper.OrderMapper;
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
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;

/**
 * 模拟支付，把待支付推到已支付待发货
 * <p>
 * 和超时释放争同一行：条件 UPDATE 谁受影响行数是 1 谁赢，输的一方照实回答而不是抛异常
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitOrderPayMcpExecutor {

    private static final String TOOL_ID = "pay_order";

    private final OrderMapper orderMapper;

    @Bean
    public McpServerFeatures.SyncToolSpecification payOrderToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("orderNo", "要支付的订单号，取自下单返回或订单查询")
                        .title("订单号"))
                .build();

        return Tool.builder()
                .name(TOOL_ID)
                .description("为当前登录用户的待支付订单完成支付，支付后订单进入已支付待发货。"
                        + "这是演示环境的模拟支付，不接真实支付渠道、不产生真实扣款。"
                        + "待支付订单超时未付会被自动取消，此时支付会失败，需要重新下单")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.WRITE)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        String userId = McpToolResults.userId(request);
        if (userId == null) {
            return McpToolResults.identityRequired(TOOL_ID);
        }
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String orderNo = StrUtil.trimToNull(MapUtil.getStr(args, "orderNo"));
            if (orderNo == null) {
                return BitToolSupport.rejected("请先确认要支付哪一笔订单，订单号取自下单返回或订单查询");
            }

            int paid = orderMapper.pay(orderNo, userId);
            if (paid == 0) {
                return BitToolSupport.rejected(explainFailure(orderNo, userId));
            }

            OrderDO order = loadOrder(orderNo, userId);
            log.info("MCP 工具调用完成, toolId={}, orderNo={}, elapsed={}ms",
                    TOOL_ID, orderNo, System.currentTimeMillis() - startMs);
            return McpToolResults.success(String.format("""
                    订单 %s 支付成功

                    实付金额: %s
                    当前状态: 已支付待发货

                    这是演示环境的模拟支付，不产生真实扣款。仓库会尽快安排发货，发货后可用物流查询跟进""",
                    orderNo, BitToolSupport.money(order == null ? BigDecimal.ZERO : order.getPayAmount())));
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("订单支付", e);
        }
    }

    /**
     * 没付成必须说清是哪一种没付成：超时被取消和已经付过，用户的下一步完全不同
     */
    private String explainFailure(String orderNo, String userId) {
        OrderDO order = loadOrder(orderNo, userId);
        if (order == null) {
            return "未找到订单 " + orderNo;
        }
        String guide = switch (order.getStatus()) {
            case BitOrderReleaser.STATUS_PAID -> "这笔订单已经支付过了，不会重复扣款";
            case BitOrderReleaser.STATUS_CANCELLED ->
                    "订单已取消，可能是超时未支付被自动释放，库存与券都已退回，需要的话可以重新下单";
            default -> "订单已经不在待支付状态，无需再支付";
        };
        return String.format("订单 %s 当前状态是%s，未执行支付。%s", orderNo, order.getStatus(), guide);
    }

    private OrderDO loadOrder(String orderNo, String userId) {
        return orderMapper.selectByNo(orderNo, userId);
    }

}

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

import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;

/**
 * 取消订单，只有没出库的两个状态能取消
 * <p>
 * 取消与归还是一件事，落在 {@link BitOrderReleaser} 上，和超时释放共用同一条路
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitOrderCancelMcpExecutor {

    private static final String TOOL_ID = "cancel_order";

    private final OrderMapper orderMapper;
    private final BitOrderReleaser bitOrderReleaser;

    @Bean
    public McpServerFeatures.SyncToolSpecification cancelOrderToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("orderNo", "要取消的订单号，来自订单查询，不要凭对话内容拼")
                        .title("订单号"))
                .build();

        return Tool.builder()
                .name(TOOL_ID)
                .description("取消当前登录用户的一笔订单，同时归还它占用的库存与优惠券。"
                        + "只有待支付和已支付待发货的订单能取消，已发货之后只能拒收或走售后。"
                        + "取消前先用订单查询确认订单号与当前状态")
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
                return BitToolSupport.rejected("请先确认要取消哪一笔订单，订单号可用订单查询取得");
            }

            OrderDO order = orderMapper.selectByNo(orderNo, userId);
            if (order == null) {
                return BitToolSupport.rejected("未找到订单 " + orderNo);
            }
            if (!BitOrderReleaser.STATUS_PENDING.equals(order.getStatus())
                    && !BitOrderReleaser.STATUS_PAID.equals(order.getStatus())) {
                return BitToolSupport.rejected(uncancellable(orderNo, order.getStatus()));
            }

            // 抢不到那一行说明状态刚被别人改了，照实说比抛异常有用
            if (!bitOrderReleaser.release(orderNo, order.getStatus())) {
                return BitToolSupport.rejected(String.format(
                        "订单 %s 的状态刚发生变化，本次没有取消成功，请重新查询订单确认当前状态", orderNo));
            }

            log.info("MCP 工具调用完成, toolId={}, orderNo={}, from={}, elapsed={}ms",
                    TOOL_ID, orderNo, order.getStatus(), System.currentTimeMillis() - startMs);
            return McpToolResults.success(buildReceipt(orderNo, order));
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("订单取消", e);
        }
    }

    private String uncancellable(String orderNo, String status) {
        String guide = switch (status) {
            case "已发货" -> "包裹已经出库，可以拒收，拒收后订单自动退款；也可以等签收后走七天无理由退货";
            case "已签收" -> "订单已签收，取消已经不适用，请改用售后申请";
            default -> "订单已经是取消状态，不需要再取消一次";
        };
        return String.format("订单 %s 当前状态是%s，未执行取消。%s", orderNo, status, guide);
    }

    private String buildReceipt(String orderNo, OrderDO order) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("订单 %s 已取消%n%n", orderNo));
        sb.append("库存已回补到商品\n");
        if (StrUtil.isNotBlank(order.getCouponCode())) {
            sb.append(String.format("优惠券 %s 已退回，状态恢复为未使用%n", order.getCouponCode()));
        }
        if (BitOrderReleaser.STATUS_PAID.equals(order.getStatus())) {
            sb.append(String.format("实付 %s 将原路退回，演示环境为模拟退款，不产生真实资金变动%n",
                    BitToolSupport.money(order.getPayAmount())));
        }
        return sb.toString().trim();
    }

}

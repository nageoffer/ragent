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
import com.nageoffer.ai.ragent.mcp.dao.entity.OrderDO;
import com.nageoffer.ai.ragent.mcp.dao.entity.OrderItemDO;
import com.nageoffer.ai.ragent.mcp.dao.mapper.OrderItemMapper;
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

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.integer;

/**
 * 订单查询，比特严选订单相关那几条路的入口
 * <p>
 * 不传单号返回最近几笔，是「我的快递到哪了」唯一开得了头的地方——用户手里通常没有订单号
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitOrderQueryMcpExecutor {

    private static final String TOOL_ID = "query_order";

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    @Bean
    public McpServerFeatures.SyncToolSpecification queryOrderToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .optional(string("orderNo", "订单号，如 88231；不填则返回最近若干笔订单摘要"))
                .optional(integer("limit", "不填订单号时返回几笔，默认 5，最多 20")
                        .defaultTo(DEFAULT_LIMIT))
                .build();

        return Tool.builder()
                .name(TOOL_ID)
                .description("查询当前登录用户的订单，只能查本账号名下的，不能按手机号、姓名或其他账号查。"
                        + "传订单号返回单笔详情，含状态、下单支付发货签收四个时间、"
                        + "商品明细、金额与是否用券、运单号、收货信息，已签收的还会给出售后窗口的截止日期与剩余天数；"
                        + "不传订单号返回最近几笔订单摘要。用户说不出订单号时先不传参数拿最近订单，"
                        + "再按用户确认的那一单继续。运单号只能从这里取，不要自行构造")
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
            Map<String, Object> args = McpToolResults.args(request);
            String orderNo = StrUtil.trimToNull(MapUtil.getStr(args, "orderNo"));
            int limit = BitToolSupport.limit(MapUtil.getInt(args, "limit"), DEFAULT_LIMIT, MAX_LIMIT);

            String result = orderNo != null
                    ? queryDetail(userId, orderNo)
                    : queryRecent(userId, limit);

            log.info("MCP 工具调用完成, toolId={}, orderNo={}, limit={}, elapsed={}ms",
                    TOOL_ID, orderNo, limit, System.currentTimeMillis() - startMs);
            return result == null ? BitToolSupport.notFound("订单 " + orderNo) : McpToolResults.success(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("订单查询", e);
        }
    }

    /**
     * 返回 null 表示这一单不存在或不属于当前用户，两种情况在调用方看来必须没有区别
     */
    private String queryDetail(String userId, String orderNo) {
        OrderDO order = orderMapper.selectByNo(orderNo, userId);
        List<OrderDO> heads = order == null ? List.of() : List.of(order);
        if (heads.isEmpty()) {
            return null;
        }
        OrderDO head = heads.get(0);
        List<OrderItemDO> items = queryItems(List.of(head.getOrderNo()));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【订单 %s】%s%n%n", head.getOrderNo(), head.getStatus()));
        sb.append(String.format("下单时间: %s%n", BitToolSupport.dateTime(head.getCreateTime())));
        appendIfPresent(sb, "支付时间", head.getPayTime());
        appendIfPresent(sb, "发货时间", head.getShipTime());
        appendIfPresent(sb, "签收时间", head.getReceiveTime());
        appendIfPresent(sb, "取消时间", head.getCancelTime());

        sb.append("\n商品:\n");
        for (int i = 0; i < items.size(); i++) {
            OrderItemDO item = items.get(i);
            sb.append(String.format("%d. %s（%s）| 单价 %s | ×%d%n",
                    i + 1, item.getSkuName(), item.getSkuCode(), BitToolSupport.money(item.getPrice()), item.getQuantity()));
        }

        sb.append(String.format("%n商品总额: %s%n", BitToolSupport.money(head.getTotalAmount())));
        if (head.getDiscountAmount() != null && head.getDiscountAmount().signum() > 0) {
            sb.append(String.format("优惠: -%s%s%n", BitToolSupport.money(head.getDiscountAmount()),
                    head.getCouponCode() == null ? "" : "（券 " + head.getCouponCode() + "）"));
        }
        // 没用券也要明说：这一行不出现，模型会照手册的通用话术告诉用户「券会退回」
        if (StrUtil.isBlank(head.getCouponCode())) {
            sb.append("优惠券: 本单未使用\n");
        }
        sb.append(String.format("实付: %s%n", BitToolSupport.money(head.getPayAmount())));

        sb.append(String.format("%n收货信息: %s | %s | %s%n", head.getReceiverName(),
                BitToolSupport.maskPhone(head.getReceiverPhone()), BitToolSupport.maskAddress(head.getReceiverAddress())));
        sb.append(String.format("运单号: %s%n", head.getTrackingNo() == null ? "尚未发货" : head.getTrackingNo()));

        if (head.getReceiveTime() != null) {
            sb.append("\n").append(BitToolSupport.returnWindow(head.getReceiveTime(), BitToolSupport.today())).append("\n");
        }
        return sb.toString().trim();
    }

    private String queryRecent(String userId, int limit) {
        List<OrderDO> heads = orderMapper.selectRecent(userId, limit);
        if (heads.isEmpty()) {
            return "当前账号名下还没有订单";
        }
        Map<String, List<OrderItemDO>> itemsByOrder = new LinkedHashMap<>();
        queryItems(heads.stream().map(OrderDO::getOrderNo).toList())
                .forEach(item -> itemsByOrder.computeIfAbsent(item.getOrderNo(), key -> new ArrayList<>()).add(item));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【最近 %d 笔订单】%n%n", heads.size()));
        for (int i = 0; i < heads.size(); i++) {
            OrderDO head = heads.get(i);
            String goods = itemsByOrder.getOrDefault(head.getOrderNo(), List.of()).stream()
                    .map(item -> item.getSkuName() + " ×" + item.getQuantity())
                    .reduce((left, right) -> left + "、" + right)
                    .orElse(BitToolSupport.EMPTY_FIELD);
            sb.append(String.format("%d. 订单 %s | %s | 实付 %s | 下单 %s%n",
                    i + 1, head.getOrderNo(), head.getStatus(), BitToolSupport.money(head.getPayAmount()),
                    BitToolSupport.dateTime(head.getCreateTime())));
            sb.append(String.format("   商品: %s%n", goods));
            if (head.getTrackingNo() != null) {
                sb.append(String.format("   运单号: %s%n", head.getTrackingNo()));
            }
        }
        return sb.toString().trim();
    }

    private List<OrderItemDO> queryItems(List<String> orderNos) {
        if (orderNos.isEmpty()) {
            return List.of();
        }
        return orderItemMapper.selectList(Wrappers.<OrderItemDO>lambdaQuery()
                .in(OrderItemDO::getOrderNo, orderNos)
                .orderByAsc(OrderItemDO::getOrderNo, OrderItemDO::getId));
    }

    private void appendIfPresent(StringBuilder sb, String label, Timestamp value) {
        if (value != null) {
            sb.append(String.format("%s: %s%n", label, BitToolSupport.dateTime(value)));
        }
    }

}

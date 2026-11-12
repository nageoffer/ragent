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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;

/**
 * 修改收货信息，只对还没出库的订单开放
 * <p>
 * 三个字段各自可改可不改，没传的一个都不动，别让「只改手机号」把地址清空
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitOrderAddressMcpExecutor {

    private static final String TOOL_ID = "change_address";

    /**
     * 出库之后改地址改不动包裹，只能走拒收或联系快递改派
     */
    private static final List<String> EDITABLE_STATUSES =
            List.of(BitOrderReleaser.STATUS_PENDING, BitOrderReleaser.STATUS_PAID);

    private static final String PHONE_PATTERN = "[0-9+\\-]{6,20}";

    private static final int MIN_ADDRESS_LENGTH = 6;

    private final OrderMapper orderMapper;

    @Bean
    public McpServerFeatures.SyncToolSpecification changeAddressToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("orderNo", "要修改的订单号，来自订单查询，不要凭对话内容拼")
                        .title("订单号"))
                .optional(string("receiverName", "新的收货人姓名，不改则不要传")
                        .title("收货人"))
                .optional(string("receiverPhone", "新的收货手机号，不改则不要传。查询返回的手机号是打码的，不要拿打码值回填")
                        .title("收货手机号"))
                .optional(string("receiverAddress", "新的完整收货地址，要到门牌号，不改则不要传。"
                        + "查询返回的地址只到区级，不要拿它拼出「完整」地址")
                        .title("收货地址"))
                .build();

        return Tool.builder()
                .name(TOOL_ID)
                .description("修改当前登录用户某笔订单的收货信息，只有待支付和已支付待发货的订单能改，"
                        + "已发货之后改不动。收货人、手机号、地址三项按用户明确说明的填，"
                        + "没说要改的那几项不要传，传了就会覆盖原值")
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
            String name = StrUtil.trimToNull(MapUtil.getStr(args, "receiverName"));
            String phone = StrUtil.trimToNull(MapUtil.getStr(args, "receiverPhone"));
            String address = StrUtil.trimToNull(MapUtil.getStr(args, "receiverAddress"));

            CallToolResult rejection = validate(orderNo, name, phone, address);
            if (rejection != null) {
                return rejection;
            }

            OrderDO order = orderMapper.selectByNo(orderNo, userId);
            if (order == null) {
                return BitToolSupport.rejected("未找到订单 " + orderNo);
            }
            String status = order.getStatus();
            if (!EDITABLE_STATUSES.contains(status)) {
                return BitToolSupport.rejected(unmodifiable(orderNo, status));
            }

            int updated = orderMapper.updateReceiver(name, phone, address, orderNo, userId);
            if (updated == 0) {
                return BitToolSupport.rejected(
                        String.format("订单 %s 的状态刚发生变化，收货信息没有改动，请重新查询订单确认当前状态", orderNo));
            }

            log.info("MCP 工具调用完成, toolId={}, orderNo={}, 改姓名={}, 改手机={}, 改地址={}, elapsed={}ms",
                    TOOL_ID, orderNo, name != null, phone != null, address != null,
                    System.currentTimeMillis() - startMs);
            return McpToolResults.success(buildReceipt(orderNo, status, name, phone, address));
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("收货信息修改", e);
        }
    }

    private CallToolResult validate(String orderNo, String name, String phone, String address) {
        if (orderNo == null) {
            return BitToolSupport.rejected("请先确认要修改哪一笔订单，订单号可用订单查询取得");
        }
        if (name == null && phone == null && address == null) {
            return BitToolSupport.rejected("没有要修改的内容，收货人、手机号、地址至少提供一项");
        }
        if (phone != null && !phone.matches(PHONE_PATTERN)) {
            return BitToolSupport.rejected("收货手机号格式不正确，请向用户确认完整号码后再提交");
        }
        if (address != null && address.length() < MIN_ADDRESS_LENGTH) {
            return BitToolSupport.rejected("收货地址太短，需要省市区与详细门牌号，请向用户确认完整地址");
        }
        return null;
    }

    private String unmodifiable(String orderNo, String status) {
        String guide = switch (status) {
            case "已发货" -> "包裹已经出库，改地址已经来不及，可以拒收后重新下单，或直接联系快递改派";
            case "已签收" -> "订单已签收，收货信息不再可改";
            default -> "订单已取消，收货信息不再可改";
        };
        return String.format("订单 %s 当前状态是%s，收货信息未修改。%s", orderNo, status, guide);
    }

    /**
     * 本轮用户自己给的值原样回显，没传的那几项只说未变动
     * <p>
     * 库里存的收货信息该打码还是打码，但刚从用户嘴里过来的值再打码，用户就无从确认存对没有
     */
    private String buildReceipt(String orderNo, String status, String name, String phone, String address) {
        List<String> changed = new ArrayList<>();
        if (name != null) {
            changed.add("收货人: " + name);
        }
        if (phone != null) {
            changed.add("手机号: " + phone);
        }
        if (address != null) {
            changed.add("地址: " + address);
        }
        return String.format("""
                订单 %s 的收货信息已更新（当前状态：%s）

                %s

                其余收货信息未变动""", orderNo, status, String.join("\n", changed));
    }
}

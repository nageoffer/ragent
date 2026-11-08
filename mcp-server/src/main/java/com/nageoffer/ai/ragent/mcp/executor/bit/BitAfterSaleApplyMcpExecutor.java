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
import com.nageoffer.ai.ragent.mcp.dao.entity.AfterSaleDO;
import com.nageoffer.ai.ragent.mcp.dao.entity.OrderDO;
import com.nageoffer.ai.ragent.mcp.dao.entity.OrderItemDO;
import com.nageoffer.ai.ragent.mcp.dao.mapper.AfterSaleMapper;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;

/**
 * 发起售后：退货退款、换货、仅退款
 * <p>
 * 窗口分三档按签收时间现算，同一单同一件已有未结单则回原单号——两条都不指望手册讲对
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitAfterSaleApplyMcpExecutor {

    private static final String TOOL_ID = "apply_after_sale";

    private static final String STATUS_PENDING = "待处理";

    /**
     * 未结束的两个状态，与部分唯一索引 uk_after_sale_open 的条件必须一致
     */
    private static final List<String> OPEN_STATUSES = List.of(STATUS_PENDING, "处理中");

    private static final List<String> TYPES = List.of("退货退款", "换货", "仅退款");

    /**
     * 七天窗口过后只受理质量问题，「仅退款」不在其中——那一档的典型场景是货没收到，该走转人工
     */
    private static final List<String> QUALITY_TYPES = List.of("退货退款", "换货");

    /**
     * 与 t_after_sale.reason 的列宽一致，超长就截断而不是让数据库抛异常
     */
    private static final int MAX_REASON_LENGTH = 256;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final AfterSaleMapper afterSaleMapper;

    @Bean
    public McpServerFeatures.SyncToolSpecification applyAfterSaleToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("orderNo", "要申请售后的订单号，来自订单查询")
                        .title("订单号"))
                .required(string("skuCode", "要申请售后的商品 SKU 型号，取自该订单的商品明细。一单多件时只对这一件生效")
                        .title("商品型号"))
                .required(string("type", "售后类型：退货退款要把货寄回，换货换同款，仅退款适用于未收到货或货已丢失")
                        .title("售后类型")
                        .options(TYPES))
                .required(string("reason", "申请原因，据实填写用户说明的问题，不要代为编造")
                        .title("申请原因"))
                .build();

        return Tool.builder()
                .name(TOOL_ID)
                .description("为当前登录用户的某笔订单发起售后申请，提交后进入人工审核。"
                        + "只有已签收的订单能申请：签收七天内任何理由都收，第 8 到 15 天只收质量问题且仅限退货退款或换货，"
                        + "超过 15 天走保修。落在哪一档由本工具按签收时间判定，不要自行推算天数。"
                        + "提交前先用订单查询确认订单状态与商品型号，售后类型和原因取自用户明确说明")
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
            String skuCode = StrUtil.trimToNull(MapUtil.getStr(args, "skuCode"));
            String type = StrUtil.trimToNull(MapUtil.getStr(args, "type"));
            String reason = StrUtil.trimToNull(MapUtil.getStr(args, "reason"));

            CallToolResult rejection = validate(orderNo, skuCode, type, reason);
            if (rejection != null) {
                return rejection;
            }
            if (reason.length() > MAX_REASON_LENGTH) {
                reason = reason.substring(0, MAX_REASON_LENGTH);
            }

            OrderDO order = orderMapper.selectByNo(orderNo, userId);
            if (order == null) {
                return BitToolSupport.rejected("未找到订单 " + orderNo);
            }

            rejection = checkEligible(orderNo, order, type);
            if (rejection != null) {
                return rejection;
            }

            List<OrderItemDO> items = orderItemMapper.selectList(Wrappers.<OrderItemDO>lambdaQuery()
                    .eq(OrderItemDO::getOrderNo, orderNo).orderByAsc(OrderItemDO::getId));
            OrderItemDO item = items.stream().filter(each -> each.getSkuCode().equalsIgnoreCase(skuCode))
                    .findFirst().orElse(null);
            if (item == null) {
                return BitToolSupport.rejected(String.format("订单 %s 里没有 %s，该订单包含: %s",
                        orderNo, skuCode, items.stream().map(each -> each.getSkuName() + "（" + each.getSkuCode() + "）")
                                .reduce((left, right) -> left + "、" + right).orElse(BitToolSupport.EMPTY_FIELD)));
            }

            CallToolResult duplicate = checkDuplicate(orderNo, item);
            if (duplicate != null) {
                return duplicate;
            }

            String afterSaleNo = nextAfterSaleNo(orderNo);
            try {
                afterSaleMapper.insert(AfterSaleDO.builder()
                        .afterSaleNo(afterSaleNo).orderNo(orderNo).skuCode(item.getSkuCode())
                        .type(type).reason(reason).status(STATUS_PENDING)
                        .build());
            } catch (DuplicateKeyException e) {
                // 判过之后、插进去之前又来了一张：唯一约束才是最终裁判，这里补一次回查
                CallToolResult raced = checkDuplicate(orderNo, item);
                return raced != null ? raced : BitToolSupport.rejected("售后申请提交失败，请稍后重试");
            }

            log.info("MCP 工具调用完成, toolId={}, afterSaleNo={}, orderNo={}, skuCode={}, type={}, elapsed={}ms",
                    TOOL_ID, afterSaleNo, orderNo, item.getSkuCode(), type, System.currentTimeMillis() - startMs);
            return McpToolResults.success(buildReceipt(afterSaleNo, orderNo, item, type, reason, windowOf(order)));
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("售后申请提交", e);
        }
    }

    private CallToolResult validate(String orderNo, String skuCode, String type, String reason) {
        if (orderNo == null || skuCode == null) {
            return BitToolSupport.rejected("订单号与商品型号都要有，先用订单查询确认是哪一单的哪一件");
        }
        if (type == null || !TYPES.contains(type)) {
            return BitToolSupport.rejected("售后类型缺失或不受支持，可选值：" + String.join("、", TYPES));
        }
        if (reason == null) {
            return BitToolSupport.rejected("申请原因为必填项，请向用户确认具体问题后再提交");
        }
        return null;
    }

    /**
     * 窗口按 receive_time 现算，不采信模型算出来的天数
     * <p>
     * 三档：七天内任何理由都收，第 8 到 15 天只收质量问题，超 15 天走保修
     * 服务端只重算窗口与类型，判不了「是不是质量问题」——那正是要检测的原因
     */
    private CallToolResult checkEligible(String orderNo, OrderDO order, String type) {
        if (!"已签收".equals(order.getStatus())) {
            String guide = switch (order.getStatus()) {
                case BitOrderReleaser.STATUS_PENDING -> "这单还没支付，不需要走售后，直接取消即可";
                case BitOrderReleaser.STATUS_PAID -> "这单还没发货，取消订单比走售后快";
                case "已发货" -> "包裹还在路上，可以拒收，或等签收后再申请售后";
                default -> "订单已取消，不需要再走售后";
            };
            return BitToolSupport.rejected(
                    String.format("订单 %s 当前状态是%s，未提交售后申请。%s", orderNo, order.getStatus(), guide));
        }
        if (order.getReceiveTime() == null) {
            return BitToolSupport.rejected(
                    String.format("订单 %s 没有签收时间，无法判定七天无理由窗口，请转人工处理", orderNo));
        }
        LocalDate today = BitToolSupport.today();
        long days = BitToolSupport.daysSince(order.getReceiveTime());
        if (today.isAfter(BitToolSupport.windowDeadline(order.getReceiveTime(), BitToolSupport.QUALITY_WINDOW_DAYS))) {
            return BitToolSupport.rejected(String.format(
                    "订单 %s 已签收 %d 天，七天无理由与十五天质量问题两个窗口都已过，未提交售后申请。"
                            + "商品还在保修期内的话可以走保修，需要的话可以帮用户转人工",
                    orderNo, days));
        }
        if (today.isAfter(BitToolSupport.windowDeadline(order.getReceiveTime(), BitToolSupport.RETURN_WINDOW_DAYS))
                && !QUALITY_TYPES.contains(type)) {
            return BitToolSupport.rejected(String.format(
                    "订单 %s 已签收 %d 天，七天无理由窗口已过，这一档只受理质量问题，未提交售后申请。"
                            + "可用的类型是%s；如果是货没收到，请改走转人工",
                    orderNo, days, String.join("、", QUALITY_TYPES)));
        }
        return null;
    }

    /**
     * 走到这里已经保证已签收且有签收时间，超 15 天的在 checkEligible 就被挡掉了
     */
    private Window windowOf(OrderDO order) {
        LocalDate noReasonDeadline = BitToolSupport.windowDeadline(order.getReceiveTime(), BitToolSupport.RETURN_WINDOW_DAYS);
        return BitToolSupport.today().isAfter(noReasonDeadline) ? Window.QUALITY : Window.NO_REASON;
    }

    /**
     * 重复提交回的是原单号，不是假装成功——对用户这才是有信息量的回答
     */
    private CallToolResult checkDuplicate(String orderNo, OrderItemDO item) {
        AfterSaleDO open = afterSaleMapper.selectOne(Wrappers.<AfterSaleDO>lambdaQuery()
                .eq(AfterSaleDO::getOrderNo, orderNo)
                .eq(AfterSaleDO::getSkuCode, item.getSkuCode())
                .in(AfterSaleDO::getStatus, OPEN_STATUSES)
                .last("LIMIT 1"));
        if (open == null) {
            return null;
        }
        List<String> existing = List.of(String.format("%s（%s·%s）",
                open.getAfterSaleNo(), open.getType(), open.getStatus()));
        return BitToolSupport.rejected(String.format(
                "订单 %s 的 %s 已经有一张未结束的售后单 %s，未重复提交。可以用售后进度查询看处理到哪一步了",
                orderNo, item.getSkuName(), existing.get(0)));
    }

    private String nextAfterSaleNo(String orderNo) {
        long existing = afterSaleMapper.selectCount(Wrappers.<AfterSaleDO>lambdaQuery()
                .eq(AfterSaleDO::getOrderNo, orderNo));
        return String.format("AS%s%03d", orderNo, existing + 1);
    }

    private String buildReceipt(String afterSaleNo, String orderNo, OrderItemDO item,
                                String type, String reason, Window window) {
        String next = switch (type) {
            case "退货退款" -> "审核通过后会给出寄回地址，货到并验收后原路退款";
            case "换货" -> "审核通过后会安排上门取件，换出的新品在旧品验收后寄出";
            default -> "审核通过后原路退款，一般 1 至 3 个工作日到账";
        };
        String basis = window == Window.QUALITY
                ? "质量问题售后（已签收超过七天，需经检测确认）"
                : "七天无理由";
        // 这一档能不能退是检测说了算，回执里先把话堵住，别让模型替检测下结论
        String caveat = window == Window.QUALITY
                ? "能否退货或换货以检测结论为准，不要向用户承诺一定能退。"
                : "";
        return String.format("""
                售后申请已提交

                售后单号: %s
                订单号: %s
                商品: %s（%s）
                类型: %s
                受理依据: %s
                原因: %s
                状态: 待处理

                %s。%s请把售后单号告知用户，后续可用售后进度查询跟进""",
                afterSaleNo, orderNo, item.getSkuName(), item.getSkuCode(), type, basis, reason, next, caveat);
    }

    /**
     * 签收后的两段受理窗口
     */
    private enum Window {

        NO_REASON,

        QUALITY
    }


}

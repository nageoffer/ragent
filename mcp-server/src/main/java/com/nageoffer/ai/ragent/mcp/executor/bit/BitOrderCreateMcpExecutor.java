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
import com.nageoffer.ai.ragent.mcp.config.bit.BitProperties;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.nageoffer.ai.ragent.mcp.dao.entity.CartDO;
import com.nageoffer.ai.ragent.mcp.dao.entity.OrderDO;
import com.nageoffer.ai.ragent.mcp.dao.entity.OrderItemDO;
import com.nageoffer.ai.ragent.mcp.dao.mapper.CartMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.OrderItemMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.OrderMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.ProductSkuMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.UserCouponMapper;
import com.nageoffer.ai.ragent.mcp.dao.result.CartLineResult;
import com.nageoffer.ai.ragent.mcp.dao.result.HeldCouponResult;
import com.nageoffer.ai.ragent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.ragent.mcp.executor.McpToolException;
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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.array;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.integer;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;

/**
 * 从购物车下单，一个事务里走完校验、扣库存、核销券、建单、扣减购物车
 * <p>
 * 金额、库存、券三样全部服务端现算现扣，模型转述的价格和可用性一律不采信
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitOrderCreateMcpExecutor {

    private static final String TOOL_ID = "create_order";

    private final CartMapper cartMapper;
    private final ProductSkuMapper productSkuMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final UserCouponMapper userCouponMapper;
    private final TransactionTemplate bitTransactionTemplate;
    private final BitProperties bitProperties;

    @Bean
    public McpServerFeatures.SyncToolSpecification createOrderToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .required(array("items", "要购买的商品列表，每项填写 skuCode 和 quantity。"
                        + "数量是本次购买数量，可以少于购物车数量；买全部商品也要逐项列出型号与数量。",
                        McpToolSchema.object()
                                .required(string("skuCode", "商品 SKU 型号，来自购物车查询").title("商品型号"))
                                .required(integer("quantity", "本次购买数量，必须为正整数").title("数量")))
                        .title("商品与数量"))
                .optional(string("couponCode", "要使用的优惠券编码，来自券包查询。不确定能不能用就先查券包试算，"
                        + "能不能用最终由本工具判定")
                        .title("优惠券编码"))
                .optional(string("receiverName", "收货人姓名，不传则沿用该用户最近一笔订单的收货信息")
                        .title("收货人"))
                .optional(string("receiverPhone", "收货手机号，不传则沿用最近一笔订单的。查询返回的手机号是打码的，不要拿打码值回填")
                        .title("收货手机号"))
                .optional(string("receiverAddress", "完整收货地址，要到门牌号，不传则沿用最近一笔订单的。"
                        + "查询返回的地址只到区级，不要拿它拼出「完整」地址")
                        .title("收货地址"))
                .build();

        return Tool.builder()
                .name(TOOL_ID)
                .description("用当前登录用户购物车里的商品创建订单，下单后为待支付状态，需要再调用支付工具完成支付。"
                        + "下单会占用库存并核销所选优惠券，商品单价与优惠金额以本工具返回的为准，不要自行计算后告知用户。"
                        + "下单前先用购物车查询确认商品与数量，按 items 中的数量购买，购物车剩余数量保留")
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
            Map<String, Integer> quantities = parseItems(args.get("items"));
            String couponCode = StrUtil.trimToNull(MapUtil.getStr(args, "couponCode"));
            Receiver input = new Receiver(
                    StrUtil.trimToNull(MapUtil.getStr(args, "receiverName")),
                    StrUtil.trimToNull(MapUtil.getStr(args, "receiverPhone")),
                    StrUtil.trimToNull(MapUtil.getStr(args, "receiverAddress")));

            CallToolResult result = bitTransactionTemplate.execute(
                    status -> placeOrder(status, userId, quantities, couponCode, input));

            log.info("MCP 工具调用完成, toolId={}, 指定商品={}, 用券={}, elapsed={}ms",
                    TOOL_ID, quantities.size(), couponCode != null, System.currentTimeMillis() - startMs);
            return result;
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("下单", e);
        }
    }

    private Map<String, Integer> parseItems(Object value) {
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            throw new McpToolException("请在 items 中逐项填写商品型号和购买数量，订单未创建");
        }
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> fields)
                    || !(fields.get("skuCode") instanceof String code) || StrUtil.isBlank(code)
                    || !(fields.get("quantity") instanceof Number quantity)) {
                throw new McpToolException("每项商品必须填写 skuCode 和正整数 quantity，订单未创建");
            }
            int count;
            try {
                count = new BigDecimal(quantity.toString()).intValueExact();
            } catch (NumberFormatException | ArithmeticException e) {
                throw new McpToolException("购买数量必须为正整数且不超过整数范围，订单未创建");
            }
            if (count <= 0) {
                throw new McpToolException("购买数量必须大于 0，订单未创建");
            }
            if (quantities.putIfAbsent(code.trim(), count) != null) {
                throw new McpToolException("同一商品型号不能重复填写，订单未创建");
            }
        }
        return quantities;
    }

    /**
     * 六步全在一个事务里，任何一步不成立就整单回滚
     * <p>
     * 回绝路径也 setRollbackOnly：前面几步可能已经扣了库存，让它们留在库里就是漏
     */
    private CallToolResult placeOrder(TransactionStatus status, String userId,
                                      Map<String, Integer> quantities, String couponCode, Receiver input) {
        List<String> skuCodes = new ArrayList<>(quantities.keySet());
        List<CartLineResult> lines = loadLines(userId, skuCodes);
        CallToolResult rejection = checkLines(lines, quantities);
        if (rejection != null) {
            status.setRollbackOnly();
            return rejection;
        }

        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> categoryAmounts = new LinkedHashMap<>();
        for (CartLineResult line : lines) {
            BigDecimal subtotal = line.getPrice().multiply(BigDecimal.valueOf(line.getQuantity()));
            total = total.add(subtotal);
            categoryAmounts.merge(line.getCategory(), subtotal, BigDecimal::add);
        }

        BigDecimal discount = BigDecimal.ZERO;
        String couponLabel = null;
        if (couponCode != null) {
            HeldCouponResult coupon = userCouponMapper.selectHeldOne(userId, couponCode);
            if (coupon == null) {
                status.setRollbackOnly();
                return BitToolSupport.rejected(String.format("未找到优惠券 %s，先用券包查询确认编码", couponCode));
            }
            BitCouponRules.Verdict verdict = BitCouponRules.judge(coupon, total, categoryAmounts);
            if (!verdict.usable()) {
                status.setRollbackOnly();
                return BitToolSupport.rejected(String.format("%s（%s）%s，订单未创建",
                        coupon.getName(), coupon.getCouponCode(), verdict.reason()));
            }
            discount = verdict.discount();
            couponLabel = String.format("%s（%s），抵扣 %s",
                    coupon.getName(), coupon.getCouponCode(), BitToolSupport.money(discount));
        }

        Receiver receiver = resolveReceiver(userId, input);
        if (receiver == null) {
            status.setRollbackOnly();
            return BitToolSupport.rejected("没有可用的收货信息，请向用户确认收货人、手机号和完整地址后再下单");
        }

        for (CartLineResult line : lines) {
            int deducted = productSkuMapper.deductStock(line.getSkuCode(), line.getQuantity());
            if (deducted == 0) {
                status.setRollbackOnly();
                return BitToolSupport.rejected(String.format(
                        "%s（%s）的库存刚被买走，本单未创建，请重新查询库存后再试", line.getSkuName(), line.getSkuCode()));
            }
        }

        String orderNo = IdWorker.getIdStr();
        BigDecimal payAmount = total.subtract(discount).max(BigDecimal.ZERO);
        orderMapper.insert(OrderDO.builder()
                .orderNo(orderNo).userId(userId).status(BitOrderReleaser.STATUS_PENDING)
                .totalAmount(total).discountAmount(discount).payAmount(payAmount)
                .couponCode(couponCode).receiverName(receiver.name())
                .receiverPhone(receiver.phone()).receiverAddress(receiver.address())
                .build());
        for (CartLineResult line : lines) {
            orderItemMapper.insert(OrderItemDO.builder()
                    .orderNo(orderNo).skuCode(line.getSkuCode()).skuName(line.getSkuName())
                    .price(line.getPrice()).quantity(line.getQuantity())
                    .build());
        }

        if (couponCode != null) {
            int used = userCouponMapper.use(orderNo, userId, couponCode);
            if (used == 0) {
                status.setRollbackOnly();
                return BitToolSupport.rejected(String.format(
                        "优惠券 %s 刚被另一笔订单用掉，本单未创建，可以不用券重新下单", couponCode));
            }
        }

        for (CartLineResult line : lines) {
            if (cartMapper.deductQuantity(userId, line.getSkuCode(), line.getQuantity()) != 1) {
                throw new IllegalStateException("锁定的购物车行扣减失败");
            }
        }
        List<String> ordered = lines.stream().map(CartLineResult::getSkuCode).toList();
        cartMapper.delete(Wrappers.<CartDO>lambdaQuery()
                .eq(CartDO::getUserId, userId).in(CartDO::getSkuCode, ordered).eq(CartDO::getQuantity, 0));

        log.info("订单已创建, orderNo={}, userId={}, 商品行={}, 合计={}, 优惠={}, 实付={}",
                orderNo, userId, lines.size(), total, discount, payAmount);
        return McpToolResults.success(
                buildReceipt(orderNo, lines, total, discount, payAmount, couponLabel, receiver, input));
    }

    private List<CartLineResult> loadLines(String userId, List<String> skuCodes) {
        return cartMapper.selectLinesForUpdate(userId, skuCodes);
    }

    /**
     * 下架与库存不足在这里一次说清，别让用户下单失败三次才知道是同一件商品的问题
     */
    private CallToolResult checkLines(List<CartLineResult> lines, Map<String, Integer> quantities) {
        List<String> skuCodes = new ArrayList<>(quantities.keySet());
        if (lines.isEmpty()) {
            return BitToolSupport.rejected("购物车里没有这些商品: " + String.join("、", skuCodes));
        }
        List<String> missing = new ArrayList<>(skuCodes);
        lines.forEach(line -> missing.remove(line.getSkuCode()));
        if (!missing.isEmpty()) {
            return BitToolSupport.rejected(String.format(
                    "购物车里没有 %s，本单未创建。可以先加入购物车，或只买车里已有的商品",
                    String.join("、", missing)));
        }
        for (CartLineResult line : lines) {
            int quantity = quantities.get(line.getSkuCode());
            if (line.getQuantity() < quantity) {
                return BitToolSupport.rejected(String.format(
                        "%s（%s）购物车数量不足，要买 %d 件但车里只有 %d 件，本单未创建",
                        line.getSkuName(), line.getSkuCode(), quantity, line.getQuantity()));
            }
            // 后续库存校验、计价、订单明细与购物车扣减均使用本次购买数量。
            line.setQuantity(quantity);
            if (!"在售".equals(line.getStatus())) {
                return BitToolSupport.rejected(String.format(
                        "%s（%s）已下架，本单未创建。把它从购物车移除后可以继续买其余商品",
                        line.getSkuName(), line.getSkuCode()));
            }
            if (line.getStock() <= 0) {
                return BitToolSupport.rejected(String.format(
                        "%s（%s）已经缺货，本单未创建。把它从购物车移除后可以继续买其余商品",
                        line.getSkuName(), line.getSkuCode()));
            }
            if (line.getStock() < line.getQuantity()) {
                return BitToolSupport.rejected(String.format(
                        "%s（%s）库存不足，要 %d 件但只剩 %d 件，本单未创建。把数量调到 %d 件可以继续下单",
                        line.getSkuName(), line.getSkuCode(), line.getQuantity(), line.getStock(), line.getStock()));
            }
        }
        return null;
    }

    /**
     * 三个字段逐项回落到最近一笔订单，本轮传了哪项就用哪项
     */
    private Receiver resolveReceiver(String userId, Receiver input) {
        if (input.complete()) {
            return input;
        }
        OrderDO history = orderMapper.selectLatestReceiver(userId);
        Receiver last = history == null
                ? new Receiver(null, null, null)
                : new Receiver(history.getReceiverName(), history.getReceiverPhone(),
                        history.getReceiverAddress());
        Receiver merged = new Receiver(
                input.name() != null ? input.name() : last.name(),
                input.phone() != null ? input.phone() : last.phone(),
                input.address() != null ? input.address() : last.address());
        return merged.complete() ? merged : null;
    }

    private String buildReceipt(String orderNo, List<CartLineResult> lines, BigDecimal total, BigDecimal discount,
                                BigDecimal payAmount, String couponLabel, Receiver receiver, Receiver input) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("订单已创建，当前为待支付%n%n订单号: %s%n%n商品明细:%n", orderNo));
        for (CartLineResult line : lines) {
            sb.append(String.format("- %s（%s）×%d，单价 %s，小计 %s%n",
                    line.getSkuName(), line.getSkuCode(), line.getQuantity(), BitToolSupport.money(line.getPrice()),
                    BitToolSupport.money(line.getPrice().multiply(BigDecimal.valueOf(line.getQuantity())))));
        }
        sb.append(String.format("%n商品合计: %s%n", BitToolSupport.money(total)));
        if (couponLabel != null) {
            sb.append(String.format("优惠券: %s%n", couponLabel));
        }
        sb.append(String.format("应付金额: %s%n%n", BitToolSupport.money(payAmount)));

        // 本轮用户自己给的原样回显，沿用历史订单的那几项照旧打码
        sb.append(String.format("收货人: %s%n", receiver.name()));
        sb.append(String.format("手机号: %s%n", input.phone() != null
                ? receiver.phone() : BitToolSupport.maskPhone(receiver.phone())));
        sb.append(String.format("收货地址: %s%n", input.address() != null
                ? receiver.address() : BitToolSupport.maskAddress(receiver.address())));
        if (!input.complete()) {
            sb.append("（未填写的收货信息沿用了最近一笔订单）\n");
        }

        sb.append(String.format("%n请提醒用户在 %d 分钟内完成支付，超时订单会自动取消并退回库存与优惠券。"
                        + "演示环境为模拟支付，不产生真实扣款",
                bitProperties.getPendingOrder().getTimeout().toMinutes()));
        return sb.toString().trim();
    }

    private Object[] concat(String userId, List<String> rest) {
        List<Object> params = new ArrayList<>();
        params.add(userId);
        params.addAll(rest);
        return params.toArray();
    }


    private record Receiver(String name, String phone, String address) {

        boolean complete() {
            return name != null && phone != null && address != null;
        }
    }
}

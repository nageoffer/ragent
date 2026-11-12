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
import com.nageoffer.ai.ragent.mcp.dao.mapper.UserCouponMapper;
import com.nageoffer.ai.ragent.mcp.dao.result.HeldCouponResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.number;

/**
 * 优惠券查询与可用性试算
 * <p>
 * 过期与否、门槛够不够、品类对不对，全在这儿现算，不采信库里存的持券状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitCouponQueryMcpExecutor {

    private static final String TOOL_ID = "query_coupons";

    private static final String DEFAULT_STATUS = BitCouponRules.STATUS_UNUSED;
    private static final String STATUS_ALL = "全部";
    private static final List<String> STATUSES = List.of(
            DEFAULT_STATUS, BitCouponRules.STATUS_USED, BitCouponRules.STATUS_EXPIRED, STATUS_ALL);

    private final UserCouponMapper userCouponMapper;

    @Bean
    public McpServerFeatures.SyncToolSpecification queryCouponsToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .optional(number("amount", "本次结算总金额，填了才会逐张试算能不能用、能抵多少"))
                .optional(string("categoryAmounts", "本次结算的品类小计，形如 手机:3999,耳机:1200，从购物车查询的「品类小计」一行照抄；"
                        + "不知道金额时只写品类名也可以，如 手机,耳机"))
                .optional(string("status", "持券状态，默认只看未使用的")
                        .options(STATUSES)
                        .defaultTo(DEFAULT_STATUS))
                .build();

        return Tool.builder()
                .name(TOOL_ID)
                .description("查询当前登录用户的优惠券，返回名称、券码、类型、门槛、适用品类与有效期。"
                        + "传入结算金额和品类小计后，会逐张判定能否使用并算出抵扣金额，给出最优的一张。"
                        + "回答「我有什么券」「这单能便宜多少」前必须调它，不要按券名自行推算门槛和折扣。"
                        + "试算结果仅供参考，下单时以创建订单返回的实际抵扣为准")
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
            BigDecimal amount = BitToolSupport.decimal(MapUtil.getStr(args, "amount"));
            Map<String, BigDecimal> categoryAmounts = parseCategoryAmounts(MapUtil.getStr(args, "categoryAmounts"));
            String status = normalizeStatus(MapUtil.getStr(args, "status"));

            List<HeldCouponResult> coupons = userCouponMapper.selectHeld(userId);

            List<HeldCouponResult> filtered = coupons.stream()
                    .filter(coupon -> STATUS_ALL.equals(status)
                            || status.equals(BitCouponRules.effectiveStatus(coupon)))
                    .toList();

            log.info("MCP 工具调用完成, toolId={}, status={}, amount={}, held={}, matched={}, elapsed={}ms",
                    TOOL_ID, status, amount, coupons.size(), filtered.size(),
                    System.currentTimeMillis() - startMs);
            return McpToolResults.success(buildResult(filtered, status, amount, categoryAmounts));
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("优惠券查询", e);
        }
    }

    private String normalizeStatus(String requested) {
        String trimmed = StrUtil.trimToNull(requested);
        return trimmed != null && STATUSES.contains(trimmed) ? trimmed : DEFAULT_STATUS;
    }

    /**
     * 认 `手机:3999` 和光写 `手机` 两种写法，后者金额留空按合计金额兜底判门槛
     */
    private Map<String, BigDecimal> parseCategoryAmounts(String raw) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (String piece : BitToolSupport.csv(raw)) {
            int index = piece.indexOf(':');
            if (index < 0) {
                index = piece.indexOf('：');
            }
            if (index <= 0) {
                result.putIfAbsent(piece, null);
                continue;
            }
            String category = StrUtil.trim(piece.substring(0, index));
            if (StrUtil.isNotBlank(category)) {
                result.put(category, BitToolSupport.decimal(piece.substring(index + 1)));
            }
        }
        return result;
    }

    private String buildResult(List<HeldCouponResult> coupons, String status, BigDecimal amount,
                               Map<String, BigDecimal> categoryAmounts) {
        if (coupons.isEmpty()) {
            return STATUS_ALL.equals(status)
                    ? "当前账号名下没有优惠券"
                    : "当前账号名下没有" + status + "的优惠券";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【优惠券 %d 张·%s】%n", coupons.size(), status));

        HeldCouponResult best = null;
        BigDecimal bestDiscount = BigDecimal.ZERO;

        for (HeldCouponResult coupon : coupons) {
            String effective = BitCouponRules.effectiveStatus(coupon);
            sb.append(String.format("%n%s（%s）%n", coupon.getName(), coupon.getCouponCode()));
            sb.append(String.format("   类型: %s | %s%n", coupon.getType(), BitCouponRules.rule(coupon)));
            sb.append(String.format("   适用品类: %s%n",
                    coupon.getCategory() == null ? BitCouponRules.ALL_CATEGORY : coupon.getCategory()));
            sb.append(String.format("   有效期: %s 至 %s%n",
                    BitToolSupport.date(coupon.getValidFrom()), BitToolSupport.date(coupon.getValidTo())));
            sb.append(String.format("   状态: %s%n", effective));
            if (coupon.getOrderNo() != null) {
                sb.append(String.format("   已用于订单: %s（%s）%n",
                        coupon.getOrderNo(), BitToolSupport.dateTime(coupon.getUseTime())));
            }

            if (amount == null || !DEFAULT_STATUS.equals(effective)) {
                continue;
            }
            BitCouponRules.Verdict verdict = BitCouponRules.judge(coupon, amount, categoryAmounts);
            sb.append(String.format("   本单试算: %s%n", verdict.reason()));
            if (verdict.usable() && verdict.discount().compareTo(bestDiscount) > 0) {
                best = coupon;
                bestDiscount = verdict.discount();
            }
        }

        if (amount != null) {
            sb.append(String.format("%n结算金额: %s%n", BitToolSupport.money(amount)));
            sb.append(best == null
                    ? "本单暂时没有可用的券"
                    : String.format("最优选择: %s（%s），可抵扣 %s，实付 %s",
                    best.getName(), best.getCouponCode(), BitToolSupport.money(bestDiscount),
                    BitToolSupport.money(amount.subtract(bestDiscount))));
        }
        return sb.toString().trim();
    }

}

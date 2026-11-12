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

import com.nageoffer.ai.ragent.mcp.dao.result.HeldCouponResult;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.Map;

/**
 * 优惠券的判定规则
 * <p>
 * 查询工具的试算和下单时的实扣共用这一份：分成两套，迟早会漂成
 * 「查的时候说能用，下单说不能用」，而那种不一致最难查
 */
final class BitCouponRules {

    static final String STATUS_UNUSED = "未使用";
    static final String STATUS_USED = "已使用";
    static final String STATUS_EXPIRED = "已过期";

    static final String ALL_CATEGORY = "全品类";

    private static final String TYPE_DISCOUNT = "折扣";

    private BitCouponRules() {
    }

    /**
     * 库里存的状态只说「有没有被用掉」，过没过期一律按当前时间现判
     */
    static String effectiveStatus(HeldCouponResult coupon) {
        if (STATUS_USED.equals(coupon.getHeldStatus())) {
            return STATUS_USED;
        }
        Timestamp now = new Timestamp(System.currentTimeMillis());
        return coupon.getValidTo() != null && coupon.getValidTo().before(now) ? STATUS_EXPIRED : STATUS_UNUSED;
    }

    /**
     * 门槛按品类小计判，拿不到小计才退回按合计判并在文案里点明
     * <p>
     * 持券状态与有效期也在这儿再判一遍：下单走的就是这条路，不能指望调用方先筛干净
     */
    static Verdict judge(HeldCouponResult coupon, BigDecimal amount, Map<String, BigDecimal> categoryAmounts) {
        String effective = effectiveStatus(coupon);
        if (!STATUS_UNUSED.equals(effective)) {
            return new Verdict(false, BigDecimal.ZERO, String.format("不可用，该券%s", effective));
        }
        if (coupon.getValidFrom() != null && coupon.getValidFrom().after(new Timestamp(System.currentTimeMillis()))) {
            return new Verdict(false, BigDecimal.ZERO,
                    String.format("不可用，该券 %s 起生效", BitToolSupport.date(coupon.getValidFrom())));
        }

        BigDecimal base = amount;
        String scope = "合计金额";
        if (coupon.getCategory() != null) {
            if (!categoryAmounts.isEmpty() && !categoryAmounts.containsKey(coupon.getCategory())) {
                return new Verdict(false, BigDecimal.ZERO,
                        String.format("不可用，本单没有%s类商品", coupon.getCategory()));
            }
            BigDecimal categoryAmount = categoryAmounts.get(coupon.getCategory());
            if (categoryAmount != null) {
                base = categoryAmount;
                scope = coupon.getCategory() + "小计";
            } else {
                scope = "合计金额（未提供" + coupon.getCategory() + "小计，结果仅供参考）";
            }
        }

        BigDecimal threshold = coupon.getThreshold() == null ? BigDecimal.ZERO : coupon.getThreshold();
        if (base.compareTo(threshold) < 0) {
            return new Verdict(false, BigDecimal.ZERO,
                    String.format("不可用，%s %s 未达门槛 %s，还差 %s",
                            scope, BitToolSupport.money(base), BitToolSupport.money(threshold),
                            BitToolSupport.money(threshold.subtract(base))));
        }

        // 折扣按 base 打：品类折扣券只该折那个品类，满减券减的是整单所以封顶在合计
        BigDecimal discount = TYPE_DISCOUNT.equals(coupon.getType())
                ? base.multiply(BigDecimal.ONE.subtract(coupon.getDiscountValue())).setScale(2, RoundingMode.HALF_UP)
                : coupon.getDiscountValue().min(amount);
        return new Verdict(true, discount,
                String.format("可用，按%s判定，可抵扣 %s", scope, BitToolSupport.money(discount)));
    }

    static String rule(HeldCouponResult coupon) {
        String threshold = coupon.getThreshold() == null || coupon.getThreshold().signum() <= 0
                ? "无门槛"
                : "满 " + BitToolSupport.money(coupon.getThreshold());
        return TYPE_DISCOUNT.equals(coupon.getType())
                ? String.format("%s，按 %s 折", threshold, discountLabel(coupon.getDiscountValue()))
                : String.format("%s，减 %s", threshold, BitToolSupport.money(coupon.getDiscountValue()));
    }

    /**
     * 0.90 读成「九」而不是「0.90」，模型照抄出去才不会说成「零点九折」
     */
    static String discountLabel(BigDecimal discountValue) {
        BigDecimal tenth = discountValue.multiply(BigDecimal.TEN).setScale(1, RoundingMode.HALF_UP);
        return tenth.stripTrailingZeros().toPlainString();
    }


    record Verdict(boolean usable, BigDecimal discount, String reason) {
    }
}

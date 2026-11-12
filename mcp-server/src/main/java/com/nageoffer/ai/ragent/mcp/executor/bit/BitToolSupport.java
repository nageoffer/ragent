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

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.mcp.executor.McpToolResults;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 比特严选各执行器共用的打码、排版与取参
 */
final class BitToolSupport {

    static final String EMPTY_FIELD = "-";

    static final int RETURN_WINDOW_DAYS = 7;

    static final int QUALITY_WINDOW_DAYS = 15;

    /**
     * 展示时间与售后窗口统一按北京时间，政策里的自然日以它为准，不随部署机器的默认时区变
     */
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 逗号、顿号、中文逗号和空白都当分隔符：模型写哪一种都得认
     */
    private static final String CSV_SEPARATOR = "[,，、\\s]+";

    /**
     * 地址截到区级时认的收尾字，「区」排在「市」前面是因为要的是区不是市
     */
    private static final List<String> DISTRICT_SUFFIXES = List.of("区", "县", "旗", "市");

    private BitToolSupport() {
    }

    /**
     * 无权访问与不存在回同一句，否则这句话本身就是一个存在性探针
     * <p>
     * 不是 isError：查不到是一个合法的答案，标成错误会让上层当成工具故障去重试
     */
    static CallToolResult notFound(String subject) {
        return McpToolResults.success("未找到" + subject);
    }

    /**
     * 写工具的回绝：这次写入没有发生，一律 isError
     * <p>
     * 和只读工具的 notFound 正好相反——那边查不到是合法答案，这边「没写成」是调用方必须知道的事实
     * 有了这条，写工具返回 success 就等价于库里真的变了；越权与不存在仍共用同一句文案
     */
    static CallToolResult rejected(String message) {
        return McpToolResults.error(message);
    }

    /**
     * 手机号留前 3 后 4；认不出的短号码退化成只留首尾，不能因为格式陌生就原样吐出去
     */
    static String maskPhone(String phone) {
        if (StrUtil.isBlank(phone)) {
            return EMPTY_FIELD;
        }
        String trimmed = StrUtil.trim(phone);
        return trimmed.length() > 7
                ? StrUtil.hide(trimmed, 3, trimmed.length() - 4)
                : StrUtil.hide(trimmed, 1, Math.max(1, trimmed.length() - 1));
    }

    /**
     * 地址只到区级，取第一个收尾字而不是最后一个
     * <p>
     * 「浦东新区张江高科技园区」里最后那个「区」已经精确到园区了，按最后一个截等于没打码
     */
    static String maskAddress(String address) {
        if (StrUtil.isBlank(address)) {
            return EMPTY_FIELD;
        }
        String trimmed = StrUtil.trim(address);
        for (String suffix : DISTRICT_SUFFIXES) {
            int index = trimmed.indexOf(suffix);
            if (index > 0) {
                return trimmed.substring(0, index + suffix.length());
            }
        }
        return trimmed.length() > 6 ? trimmed.substring(0, 6) : trimmed;
    }

    static String money(BigDecimal amount) {
        return amount == null ? "0.00" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 金额解析不出来按「没给」处理，不能兜底成 0——那会把够门槛的券判成不可用
     */
    static BigDecimal decimal(String value) {
        String trimmed = StrUtil.trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String dateTime(Timestamp value) {
        return value == null ? EMPTY_FIELD : DATE_TIME.format(value.toInstant().atZone(ZONE));
    }

    static String date(Timestamp value) {
        return value == null ? EMPTY_FIELD : dateOf(value).toString();
    }

    static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    private static LocalDate dateOf(Timestamp value) {
        return value.toInstant().atZone(ZONE).toLocalDate();
    }

    /**
     * 距今过了几个自然日，按日历日相减，不按 24 小时一段截断
     */
    static long daysSince(Timestamp value) {
        return value == null ? -1 : ChronoUnit.DAYS.between(dateOf(value), today());
    }

    /**
     * 售后窗口的截止日：签收次日起算第 N 个自然日，当天 24:00 截止，与签收钟点无关
     */
    static LocalDate windowDeadline(Timestamp receiveTime, int days) {
        return dateOf(receiveTime).plusDays(days);
    }

    /**
     * 两个窗口一起报，并给出截止日期：只说七天无理由过了，模型会以为售后彻底没门；只给天数，模型会自己推日期
     */
    static String returnWindow(Timestamp receiveTime, LocalDate today) {
        long signedDays = ChronoUnit.DAYS.between(dateOf(receiveTime), today);
        LocalDate noReasonEnd = windowDeadline(receiveTime, RETURN_WINDOW_DAYS);
        LocalDate qualityEnd = windowDeadline(receiveTime, QUALITY_WINDOW_DAYS);
        if (!today.isAfter(noReasonEnd)) {
            return String.format("售后窗口: 已签收 %d 天；七天无理由 %s 24:00 截止（%s）；十五天质量问题 %s 24:00 截止",
                    signedDays, noReasonEnd, daysLeft(today, noReasonEnd), qualityEnd);
        }
        if (!today.isAfter(qualityEnd)) {
            return String.format("售后窗口: 已签收 %d 天；七天无理由已于 %s 截止；十五天质量问题 %s 24:00 截止（%s）",
                    signedDays, noReasonEnd, qualityEnd, daysLeft(today, qualityEnd));
        }
        return String.format("售后窗口: 已签收 %d 天；七天无理由与十五天质量问题窗口都已过（%s 截止）",
                signedDays, qualityEnd);
    }

    private static String daysLeft(LocalDate today, LocalDate deadline) {
        long left = ChronoUnit.DAYS.between(today, deadline);
        return left == 0 ? "今天是最后一天" : String.format("今天之后还有 %d 天", left);
    }

    static List<String> csv(String value) {
        if (StrUtil.isBlank(value)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String piece : value.split(CSV_SEPARATOR)) {
            String trimmed = StrUtil.trim(piece);
            if (StrUtil.isNotBlank(trimmed) && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 返回条数有上限：模型传个 10000 进来不该把整张表读出去
     */
    static int limit(Integer requested, int defaultValue, int max) {
        return requested == null || requested <= 0 ? defaultValue : Math.min(requested, max);
    }

    /**
     * 在售零库存和只剩几件都要说出来，不然「加购的东西还能不能买」答不了
     */
    /**
     * 规格展示顺序，与写入顺序无关——jsonb 按键长与字典序重排，照它的顺序读出来
     * 会变成「容量 / 尺寸 / 颜色」，同一款的各配置之间对不齐
     */
    private static final List<String> SPEC_ORDER = List.of("容量", "颜色", "尺寸", "表壳尺寸", "表壳材质",
            "连接", "芯片", "核心配置", "面板", "支架", "充电盒", "包装规格", "屏幕");

    /**
     * 规格 JSON 排成「容量 256GB / 颜色 黑色」
     */
    static String specs(String json) {
        if (StrUtil.isBlank(json)) {
            return EMPTY_FIELD;
        }
        JSONObject obj = JSONUtil.parseObj(json);
        if (obj.isEmpty()) {
            return EMPTY_FIELD;
        }
        List<String> parts = new ArrayList<>();
        for (String key : SPEC_ORDER) {
            Object value = obj.get(key);
            if (value != null) {
                parts.add(key + " " + value);
            }
        }
        // 顺序表里没列到的键仍要出现，否则新加一个维度会静默消失
        obj.forEach((key, value) -> {
            if (!SPEC_ORDER.contains(key)) {
                parts.add(key + " " + value);
            }
        });
        return String.join(" / ", parts);
    }

    static String stockLabel(String status, int stock) {
        if (!"在售".equals(status)) {
            return status;
        }
        if (stock <= 0) {
            return "在售缺货";
        }
        return stock <= 5 ? "仅剩 " + stock + " 件" : "有货 " + stock + " 件";
    }
}

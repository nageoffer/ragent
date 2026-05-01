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

package com.nageoffer.ai.ragent.mcp.executor;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SalesMcpExecutor {

    private static final String TOOL_ID = "sales_query";

    private static final List<String> REGIONS = List.of("华东", "华南", "华北", "西南", "西北");
    private static final List<String> PRODUCTS = List.of("企业版", "专业版", "基础版");
    private static final Map<String, List<String>> SALES_BY_REGION = Map.of(
            "华东", List.of("张三", "李四", "王五"),
            "华南", List.of("赵六", "钱七", "孙八"),
            "华北", List.of("周九", "吴十", "郑冬"),
            "西南", List.of("陈春", "林夏", "黄秋"),
            "西北", List.of("刘一", "杨二", "马三")
    );
    private static final List<String> CUSTOMER_POOL = List.of(
            "腾讯科技", "阿里巴巴", "字节跳动", "美团点评", "京东集团",
            "百度在线", "网易公司", "小米科技", "华为技术", "中兴通讯",
            "用友网络", "金蝶软件", "浪潮集团", "东软集团", "科大讯飞",
            "三一重工", "中联重科", "格力电器", "美的集团", "海尔智家"
    );

    private List<SalesRecord> cachedData;
    private String cacheKey;

    @Bean
    public McpServerFeatures.SyncToolSpecification salesToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(buildTool(),
                (exchange, request) -> handleCall(request));
    }

    private Tool buildTool() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("region", Map.of(
                "type", "string",
                "description", "地区筛选：华东、华南、华北、西南、西北，不填则查询全国",
                "enum", List.of("华东", "华南", "华北", "西南", "西北")
        ));

        properties.put("period", Map.of(
                "type", "string",
                "description", "时间段：本月、上月、本季度、上季度、本年，默认本月",
                "enum", List.of("本月", "上月", "本季度", "上季度", "本年"),
                "default", "本月"
        ));

        properties.put("product", Map.of(
                "type", "string",
                "description", "产品筛选：企业版、专业版、基础版，不填则查询全部产品",
                "enum", List.of("企业版", "专业版", "基础版")
        ));

        properties.put("salesPerson", Map.of(
                "type", "string",
                "description", "销售人员姓名，不填则查询全部销售"
        ));

        properties.put("queryType", Map.of(
                "type", "string",
                "description", "查询类型：summary(汇总)、ranking(排名)、detail(明细)、trend(趋势)",
                "enum", List.of("summary", "ranking", "detail", "trend"),
                "default", "summary"
        ));

        properties.put("limit", Map.of(
                "type", "integer",
                "description", "返回记录数限制，默认10",
                "default", 10
        ));

        JsonSchema inputSchema = new JsonSchema(
                "object", properties, List.of(), null, null, null);

        return Tool.builder()
                .name(TOOL_ID)
                .description("查询软件销售数据，支持按地区、时间、产品、销售人员等维度筛选，支持汇总统计、排名、明细列表等多种查询")
                .inputSchema(inputSchema)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
            String region = stringArg(args, "region");
            String period = stringArg(args, "period");
            String product = stringArg(args, "product");
            String salesPerson = stringArg(args, "salesPerson");
            String queryType = stringArg(args, "queryType");
            Integer limit = intArg(args, "limit");

            if (period == null || period.isBlank()) period = "本月";
            if (queryType == null || queryType.isBlank()) queryType = "summary";
            if (limit == null || limit <= 0) limit = 10;

            List<SalesRecord> allData = getOrGenerateData(period);
            List<SalesRecord> filtered = filterData(allData, region, product, salesPerson);

            String result = switch (queryType) {
                case "ranking" -> buildRankingResult(filtered, region, period, limit);
                case "detail" -> buildDetailResult(filtered, region, period, limit);
                case "trend" -> buildTrendResult(filtered, region, period);
                default -> buildSummaryResult(filtered, region, period, product, salesPerson);
            };

            log.info("MCP 工具调用完成, toolId={}, queryType={}, region={}, period={}, elapsed={}ms",
                    TOOL_ID, queryType, region, period, System.currentTimeMillis() - startMs);
            return successResult(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return errorResult("查询失败: " + e.getMessage());
        }
    }

    private String buildSummaryResult(List<SalesRecord> data, String region, String period,
                                      String product, String salesPerson) {
        double totalAmount = data.stream().mapToDouble(r -> r.amount).sum();
        int orderCount = data.size();
        double avgAmount = orderCount > 0 ? totalAmount / orderCount : 0;
        Map<String, Double> byProduct = data.stream()
                .collect(Collectors.groupingBy(r -> r.product, Collectors.summingDouble(r -> r.amount)));
        Map<String, Double> byRegion = data.stream()
                .collect(Collectors.groupingBy(r -> r.region, Collectors.summingDouble(r -> r.amount)));

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(period).append(" 销售数据汇总】\n\n");
        List<String> filters = new ArrayList<>();
        if (region != null) filters.add("地区: " + region);
        if (product != null) filters.add("产品: " + product);
        if (salesPerson != null) filters.add("销售: " + salesPerson);
        if (!filters.isEmpty()) sb.append("筛选条件: ").append(String.join("，", filters)).append("\n\n");
        sb.append(String.format("总销售额: ¥%.2f 万\n", totalAmount));
        sb.append(String.format("成交订单: %d 笔\n", orderCount));
        sb.append(String.format("平均单价: ¥%.2f 万\n", avgAmount));
        if (product == null && !byProduct.isEmpty()) {
            sb.append("\n【按产品分布】\n");
            byProduct.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .forEach(e -> sb.append(String.format("  %s: ¥%.2f 万 (%.1f%%)\n", e.getKey(), e.getValue(), e.getValue() / totalAmount * 100)));
        }
        if (region == null && !byRegion.isEmpty()) {
            sb.append("\n【按地区分布】\n");
            byRegion.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .forEach(e -> sb.append(String.format("  %s: ¥%.2f 万 (%.1f%%)\n", e.getKey(), e.getValue(), e.getValue() / totalAmount * 100)));
        }
        return sb.toString().trim();
    }

    private String buildRankingResult(List<SalesRecord> data, String region, String period, int limit) {
        Map<String, Double> bySales = data.stream()
                .collect(Collectors.groupingBy(r -> r.salesPerson, Collectors.summingDouble(r -> r.amount)));
        List<Map.Entry<String, Double>> ranking = bySales.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(limit).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(period);
        if (region != null) sb.append(" ").append(region);
        sb.append(" 销售排名】\n\n");
        if (ranking.isEmpty()) {
            sb.append("暂无销售数据");
        } else {
            for (int i = 0; i < ranking.size(); i++) {
                Map.Entry<String, Double> entry = ranking.get(i);
                sb.append(String.format("第%d名: %s - ¥%.2f 万\n", i + 1, entry.getKey(), entry.getValue()));
            }
        }
        return sb.toString().trim();
    }

    private String buildDetailResult(List<SalesRecord> data, String region, String period, int limit) {
        List<SalesRecord> topRecords = data.stream().sorted((a, b) -> Double.compare(b.amount, a.amount)).limit(limit).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(period);
        if (region != null) sb.append(" ").append(region);
        sb.append(" 销售明细】\n\n");
        sb.append(String.format("共 %d 条记录，显示金额最高的 %d 条：\n\n", data.size(), topRecords.size()));
        for (int i = 0; i < topRecords.size(); i++) {
            SalesRecord r = topRecords.get(i);
            sb.append(String.format("%d. %s\n", i + 1, r.customer));
            sb.append(String.format("   产品: %s | 金额: ¥%.2f 万\n", r.product, r.amount));
            sb.append(String.format("   销售: %s | 地区: %s | 日期: %s\n\n", r.salesPerson, r.region, r.date));
        }
        return sb.toString().trim();
    }

    private String buildTrendResult(List<SalesRecord> data, String region, String period) {
        Map<String, Double> byWeek = data.stream().collect(Collectors.groupingBy(
                r -> "第" + ((LocalDate.parse(r.date).getDayOfMonth() - 1) / 7 + 1) + "周",
                Collectors.summingDouble(r -> r.amount)));
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(period);
        if (region != null) sb.append(" ").append(region);
        sb.append(" 销售趋势】\n\n");
        if (byWeek.isEmpty()) {
            sb.append("暂无数据");
        } else {
            byWeek.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                    sb.append(String.format("%s: ¥%.2f 万\n", e.getKey(), e.getValue())));
        }
        return sb.toString().trim();
    }

    private List<SalesRecord> filterData(List<SalesRecord> data, String region, String product, String salesPerson) {
        return data.stream()
                .filter(r -> region == null || region.equals(r.region))
                .filter(r -> product == null || product.equals(r.product))
                .filter(r -> salesPerson == null || salesPerson.equals(r.salesPerson))
                .toList();
    }

    private List<SalesRecord> getOrGenerateData(String period) {
        String key = period + "_" + LocalDate.now();
        if (cachedData != null && key.equals(cacheKey)) return cachedData;
        LocalDate[] dateRange = getDateRange(period);
        cachedData = generateMockData(dateRange[0], dateRange[1]);
        cacheKey = key;
        return cachedData;
    }

    private LocalDate[] getDateRange(String period) {
        LocalDate now = LocalDate.now();
        return switch (period) {
            case "上月" -> new LocalDate[]{now.minusMonths(1).withDayOfMonth(1), now.withDayOfMonth(1).minusDays(1)};
            case "本季度" -> {
                int q = (now.getMonthValue() - 1) / 3;
                yield new LocalDate[]{now.withMonth(q * 3 + 1).withDayOfMonth(1), now};
            }
            case "上季度" -> {
                int q = (now.getMonthValue() - 1) / 3;
                LocalDate end = now.withMonth(q * 3 + 1).withDayOfMonth(1).minusDays(1);
                LocalDate start = end.withMonth(((q - 1 + 4) % 4) * 3 + 1).withDayOfMonth(1);
                yield new LocalDate[]{start, end};
            }
            case "本年" -> new LocalDate[]{now.withDayOfYear(1), now};
            default -> new LocalDate[]{now.withDayOfMonth(1), now};
        };
    }

    private List<SalesRecord> generateMockData(LocalDate start, LocalDate end) {
        List<SalesRecord> records = new ArrayList<>();
        Random random = new Random(start.toEpochDay());
        long days = end.toEpochDay() - start.toEpochDay() + 1;
        for (long d = 0; d < days; d++) {
            LocalDate date = start.plusDays(d);
            if (date.getDayOfWeek().getValue() > 5) continue;
            int ordersPerDay = 3 + random.nextInt(6);
            for (int i = 0; i < ordersPerDay; i++) {
                SalesRecord record = new SalesRecord();
                record.region = REGIONS.get(random.nextInt(REGIONS.size()));
                record.salesPerson = SALES_BY_REGION.get(record.region).get(random.nextInt(3));
                record.product = PRODUCTS.get(random.nextInt(PRODUCTS.size()));
                record.customer = CUSTOMER_POOL.get(random.nextInt(CUSTOMER_POOL.size())) + date.getDayOfMonth();
                record.amount = switch (record.product) {
                    case "企业版" -> 50 + random.nextDouble() * 150;
                    case "专业版" -> 10 + random.nextDouble() * 40;
                    default -> 1 + random.nextDouble() * 9;
                };
                record.amount = Math.round(record.amount * 100) / 100.0;
                record.date = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
                records.add(record);
            }
        }
        return records;
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : null;
    }

    private static Integer intArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        return null;
    }

    private static CallToolResult successResult(String text) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(text)))
                .isError(false)
                .build();
    }

    private static CallToolResult errorResult(String message) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(message)))
                .isError(true)
                .build();
    }

    private static class SalesRecord {
        String region;
        String salesPerson;
        String product;
        String customer;
        double amount;
        String date;
    }
}

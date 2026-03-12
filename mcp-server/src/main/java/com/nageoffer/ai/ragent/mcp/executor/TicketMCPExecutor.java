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

import com.nageoffer.ai.ragent.mcp.core.MCPToolDefinition;
import com.nageoffer.ai.ragent.mcp.core.MCPToolExecutor;
import com.nageoffer.ai.ragent.mcp.core.MCPToolRequest;
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;
import lombok.extern.slf4j.Slf4j;
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
public class TicketMCPExecutor implements MCPToolExecutor {

    private static final String TOOL_ID = "ticket_query";

    private static final List<String> REGIONS = List.of("华东", "华南", "华北", "西南", "西北");
    private static final List<String> PRODUCTS = List.of("企业版", "专业版", "基础版");
    private static final List<String> STATUSES = List.of("待处理", "处理中", "已解决", "已关闭");
    private static final List<String> PRIORITIES = List.of("紧急", "高", "中", "低");
    private static final List<String> CATEGORIES = List.of("功能异常", "性能问题", "安装部署", "使用咨询", "数据问题", "权限问题");

    private static final Map<String, List<String>> CUSTOMERS_BY_REGION = Map.of(
            "华东", List.of("腾讯科技", "阿里巴巴", "字节跳动", "网易公司"),
            "华南", List.of("美团点评", "京东集团", "小米科技", "格力电器"),
            "华北", List.of("百度在线", "华为技术", "中兴通讯", "用友网络"),
            "西南", List.of("科大讯飞", "金蝶软件", "三一重工", "中联重科"),
            "西北", List.of("浪潮集团", "东软集团", "美的集团", "海尔智家")
    );

    private static final Map<String, List<String>> ENGINEERS_BY_REGION = Map.of(
            "华东", List.of("工程师A1", "工程师A2"),
            "华南", List.of("工程师B1", "工程师B2"),
            "华北", List.of("工程师C1", "工程师C2"),
            "西南", List.of("工程师D1", "工程师D2"),
            "西北", List.of("工程师E1", "工程师E2")
    );

    private static final List<String> ISSUE_TEMPLATES = List.of(
            "系统登录后页面白屏无法操作",
            "报表导出功能超时失败",
            "用户权限配置不生效",
            "数据同步延迟超过预期",
            "批量导入数据格式校验异常",
            "API接口调用返回500错误",
            "定时任务未按计划执行",
            "搜索功能结果不准确",
            "通知消息无法正常推送",
            "文件上传大小限制配置无效",
            "仪表盘数据展示不一致",
            "多租户数据隔离存在问题",
            "审批流程节点卡住无法流转",
            "移动端页面适配显示异常",
            "数据备份任务执行失败"
    );

    private List<TicketRecord> cachedData;
    private String cacheKey;

    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> parameters = new LinkedHashMap<>();

        parameters.put("region", MCPToolDefinition.ParameterDef.builder()
                .description("地区筛选：华东、华南、华北、西南、西北，不填则查询全国")
                .type("string")
                .required(false)
                .enumValues(List.of("华东", "华南", "华北", "西南", "西北"))
                .build());

        parameters.put("status", MCPToolDefinition.ParameterDef.builder()
                .description("工单状态筛选：待处理、处理中、已解决、已关闭，不填则查询全部状态")
                .type("string")
                .required(false)
                .enumValues(List.of("待处理", "处理中", "已解决", "已关闭"))
                .build());

        parameters.put("priority", MCPToolDefinition.ParameterDef.builder()
                .description("优先级筛选：紧急、高、中、低，不填则查询全部优先级")
                .type("string")
                .required(false)
                .enumValues(List.of("紧急", "高", "中", "低"))
                .build());

        parameters.put("product", MCPToolDefinition.ParameterDef.builder()
                .description("产品筛选：企业版、专业版、基础版，不填则查询全部产品")
                .type("string")
                .required(false)
                .enumValues(List.of("企业版", "专业版", "基础版"))
                .build());

        parameters.put("customerName", MCPToolDefinition.ParameterDef.builder()
                .description("客户名称关键字，支持模糊匹配")
                .type("string")
                .required(false)
                .build());

        parameters.put("queryType", MCPToolDefinition.ParameterDef.builder()
                .description("查询类型：summary(汇总概览)、list(工单列表)、stats(统计分析)")
                .type("string")
                .required(false)
                .defaultValue("summary")
                .enumValues(List.of("summary", "list", "stats"))
                .build());

        parameters.put("limit", MCPToolDefinition.ParameterDef.builder()
                .description("返回记录数限制，默认10")
                .type("integer")
                .required(false)
                .defaultValue(10)
                .build());

        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("查询客户技术支持工单数据，支持按地区、状态、优先级、产品、客户等维度筛选，支持汇总概览、工单列表、统计分析等多种查询")
                .parameters(parameters)
                .requireUserId(true)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        try {
            String region = request.getStringParameter("region");
            String status = request.getStringParameter("status");
            String priority = request.getStringParameter("priority");
            String product = request.getStringParameter("product");
            String customerName = request.getStringParameter("customerName");
            String queryType = request.getStringParameter("queryType");
            Integer limit = request.getParameter("limit");

            if (queryType == null || queryType.isBlank()) queryType = "summary";
            if (limit == null || limit <= 0) limit = 10;

            List<TicketRecord> allData = getOrGenerateData();
            List<TicketRecord> filtered = filterData(allData, region, status, priority, product, customerName);

            String result = switch (queryType) {
                case "list" -> buildListResult(filtered, limit);
                case "stats" -> buildStatsResult(filtered);
                default -> buildSummaryResult(filtered, region, status, priority, product);
            };

            return MCPToolResponse.success(TOOL_ID, result);
        } catch (Exception e) {
            log.error("工单数据查询失败", e);
            return MCPToolResponse.error(TOOL_ID, "EXECUTION_ERROR", "查询失败: " + e.getMessage());
        }
    }

    private String buildSummaryResult(List<TicketRecord> data, String region, String status,
                                       String priority, String product) {
        int total = data.size();
        long pending = data.stream().filter(t -> "待处理".equals(t.status)).count();
        long inProgress = data.stream().filter(t -> "处理中".equals(t.status)).count();
        long resolved = data.stream().filter(t -> "已解决".equals(t.status)).count();
        long closed = data.stream().filter(t -> "已关闭".equals(t.status)).count();
        long urgent = data.stream().filter(t -> "紧急".equals(t.priority)).count();
        long high = data.stream().filter(t -> "高".equals(t.priority)).count();

        StringBuilder sb = new StringBuilder();
        sb.append("【客户工单汇总概览】\n\n");

        List<String> filters = new ArrayList<>();
        if (region != null) filters.add("地区: " + region);
        if (status != null) filters.add("状态: " + status);
        if (priority != null) filters.add("优先级: " + priority);
        if (product != null) filters.add("产品: " + product);
        if (!filters.isEmpty()) sb.append("筛选条件: ").append(String.join("，", filters)).append("\n\n");

        sb.append(String.format("工单总数: %d 个\n\n", total));
        sb.append("【状态分布】\n");
        sb.append(String.format("  待处理: %d 个\n", pending));
        sb.append(String.format("  处理中: %d 个\n", inProgress));
        sb.append(String.format("  已解决: %d 个\n", resolved));
        sb.append(String.format("  已关闭: %d 个\n\n", closed));

        if (total > 0) {
            double resolveRate = (resolved + closed) * 100.0 / total;
            sb.append(String.format("解决率: %.1f%%\n", resolveRate));
        }

        if (urgent + high > 0) {
            sb.append(String.format("\n⚠ 紧急/高优先级工单: %d 个（紧急 %d，高 %d）\n", urgent + high, urgent, high));
        }

        Map<String, Long> byProduct = data.stream()
                .collect(Collectors.groupingBy(t -> t.product, Collectors.counting()));
        if (product == null && !byProduct.isEmpty()) {
            sb.append("\n【按产品分布】\n");
            byProduct.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(e -> sb.append(String.format("  %s: %d 个\n", e.getKey(), e.getValue())));
        }

        Map<String, Long> byRegion = data.stream()
                .collect(Collectors.groupingBy(t -> t.region, Collectors.counting()));
        if (region == null && !byRegion.isEmpty()) {
            sb.append("\n【按地区分布】\n");
            byRegion.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(e -> sb.append(String.format("  %s: %d 个\n", e.getKey(), e.getValue())));
        }

        return sb.toString().trim();
    }

    private String buildListResult(List<TicketRecord> data, int limit) {
        List<TicketRecord> sorted = data.stream()
                .sorted((a, b) -> {
                    int pa = PRIORITIES.indexOf(a.priority);
                    int pb = PRIORITIES.indexOf(b.priority);
                    if (pa != pb) return Integer.compare(pa, pb);
                    return b.createDate.compareTo(a.createDate);
                })
                .limit(limit)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【工单列表】共 %d 条，显示 %d 条（按优先级排序）\n\n", data.size(), sorted.size()));

        for (int i = 0; i < sorted.size(); i++) {
            TicketRecord t = sorted.get(i);
            sb.append(String.format("%d. [%s] %s\n", i + 1, t.ticketId, t.title));
            sb.append(String.format("   客户: %s | 产品: %s | 地区: %s\n", t.customer, t.product, t.region));
            sb.append(String.format("   优先级: %s | 状态: %s | 分类: %s\n", t.priority, t.status, t.category));
            sb.append(String.format("   处理人: %s | 创建时间: %s\n\n", t.engineer, t.createDate));
        }

        return sb.toString().trim();
    }

    private String buildStatsResult(List<TicketRecord> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("【工单统计分析】\n\n");

        if (data.isEmpty()) {
            sb.append("暂无工单数据");
            return sb.toString();
        }

        Map<String, Long> byCategory = data.stream()
                .collect(Collectors.groupingBy(t -> t.category, Collectors.counting()));
        sb.append("【问题分类统计】\n");
        byCategory.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> sb.append(String.format("  %s: %d 个 (%.1f%%)\n",
                        e.getKey(), e.getValue(), e.getValue() * 100.0 / data.size())));

        sb.append("\n【各产品解决率】\n");
        Map<String, List<TicketRecord>> byProduct = data.stream()
                .collect(Collectors.groupingBy(t -> t.product));
        byProduct.forEach((product, tickets) -> {
            long resolvedCount = tickets.stream()
                    .filter(t -> "已解决".equals(t.status) || "已关闭".equals(t.status)).count();
            sb.append(String.format("  %s: %.1f%% (%d/%d)\n",
                    product, resolvedCount * 100.0 / tickets.size(), resolvedCount, tickets.size()));
        });

        sb.append("\n【处理人工单量排名】\n");
        Map<String, Long> byEngineer = data.stream()
                .filter(t -> "待处理".equals(t.status) || "处理中".equals(t.status))
                .collect(Collectors.groupingBy(t -> t.engineer, Collectors.counting()));
        byEngineer.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .forEach(e -> sb.append(String.format("  %s: %d 个待处理\n", e.getKey(), e.getValue())));

        return sb.toString().trim();
    }

    private List<TicketRecord> filterData(List<TicketRecord> data, String region, String status,
                                           String priority, String product, String customerName) {
        return data.stream()
                .filter(t -> region == null || region.equals(t.region))
                .filter(t -> status == null || status.equals(t.status))
                .filter(t -> priority == null || priority.equals(t.priority))
                .filter(t -> product == null || product.equals(t.product))
                .filter(t -> customerName == null || t.customer.contains(customerName))
                .toList();
    }

    private List<TicketRecord> getOrGenerateData() {
        String key = "tickets_" + LocalDate.now();
        if (cachedData != null && key.equals(cacheKey)) return cachedData;
        cachedData = generateMockData();
        cacheKey = key;
        return cachedData;
    }

    private List<TicketRecord> generateMockData() {
        List<TicketRecord> records = new ArrayList<>();
        LocalDate today = LocalDate.now();
        Random random = new Random(today.toEpochDay());
        int ticketSeq = 1;

        for (int d = 0; d < 30; d++) {
            LocalDate date = today.minusDays(d);
            if (date.getDayOfWeek().getValue() > 5) continue;
            int ticketsPerDay = 2 + random.nextInt(5);

            for (int i = 0; i < ticketsPerDay; i++) {
                TicketRecord ticket = new TicketRecord();
                ticket.ticketId = String.format("TK-%s-%04d", today.format(DateTimeFormatter.ofPattern("yyyyMM")), ticketSeq++);
                ticket.region = REGIONS.get(random.nextInt(REGIONS.size()));
                ticket.customer = CUSTOMERS_BY_REGION.get(ticket.region).get(random.nextInt(4));
                ticket.product = PRODUCTS.get(random.nextInt(PRODUCTS.size()));
                ticket.title = ISSUE_TEMPLATES.get(random.nextInt(ISSUE_TEMPLATES.size()));
                ticket.category = CATEGORIES.get(random.nextInt(CATEGORIES.size()));
                ticket.engineer = ENGINEERS_BY_REGION.get(ticket.region).get(random.nextInt(2));
                ticket.createDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE);

                int priorityWeight = random.nextInt(100);
                if (priorityWeight < 5) ticket.priority = "紧急";
                else if (priorityWeight < 20) ticket.priority = "高";
                else if (priorityWeight < 60) ticket.priority = "中";
                else ticket.priority = "低";

                if (d > 7) {
                    ticket.status = random.nextInt(100) < 80 ? "已关闭" : "已解决";
                } else if (d > 3) {
                    int statusWeight = random.nextInt(100);
                    if (statusWeight < 30) ticket.status = "已解决";
                    else if (statusWeight < 60) ticket.status = "已关闭";
                    else if (statusWeight < 85) ticket.status = "处理中";
                    else ticket.status = "待处理";
                } else {
                    int statusWeight = random.nextInt(100);
                    if (statusWeight < 35) ticket.status = "待处理";
                    else if (statusWeight < 70) ticket.status = "处理中";
                    else if (statusWeight < 90) ticket.status = "已解决";
                    else ticket.status = "已关闭";
                }

                records.add(ticket);
            }
        }
        return records;
    }

    private static class TicketRecord {
        String ticketId;
        String region;
        String customer;
        String product;
        String title;
        String category;
        String priority;
        String status;
        String engineer;
        String createDate;
    }
}

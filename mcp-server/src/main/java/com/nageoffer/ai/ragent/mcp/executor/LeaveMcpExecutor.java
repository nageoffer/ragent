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

@Slf4j
@Component
public class LeaveMcpExecutor {

    private static final String TOOL_ID = "leave_query";// 请假查询工具

    private static final String DEFAULT_EMPLOYEE = "张三";

    private static final List<String> LEAVE_TYPES = List.of("年假", "调休", "病假", "事假");
    private static final List<String> APPROVERS = List.of("李经理", "王总监", "赵主管");

    private LeaveProfile cachedProfile;
    private String cacheKey;

    @Bean
    public McpServerFeatures.SyncToolSpecification leaveToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(buildTool(),
                (exchange, request) -> handleCall(request));
    }

    private Tool buildTool() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("employeeName", Map.of(
                "type", "string",
                "description", "员工姓名或工号，不填则查询当前登录员工"
        ));

        properties.put("leaveType", Map.of(
                "type", "string",
                "description", "假期类型：年假、调休、病假、事假，默认年假",
                "enum", LEAVE_TYPES,
                "default", "年假"
        ));

        properties.put("year", Map.of(
                "type", "integer",
                "description", "查询年度，如 2026，不填则查询当前年度"
        ));

        properties.put("queryType", Map.of(
                "type", "string",
                "description", "查询类型：balance(余额与额度)、detail(请假明细)",
                "enum", List.of("balance", "detail"),
                "default", "balance"
        ));

        JsonSchema inputSchema = new JsonSchema(
                "object", properties, List.of(), null, null, null);

        return Tool.builder()
                .name(TOOL_ID)
                .description("查询员工的假期额度与余额，支持年假、调休、病假、事假，"
                        + "年假返回全年额度、上年结转天数与截止日、已休天数和当前可用余额，也可返回请假明细")
                .inputSchema(inputSchema)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
            String employeeName = stringArg(args, "employeeName");
            String leaveType = stringArg(args, "leaveType");
            Integer year = intArg(args, "year");
            String queryType = stringArg(args, "queryType");

            if (employeeName == null || employeeName.isBlank()) employeeName = DEFAULT_EMPLOYEE;
            if (leaveType == null || !LEAVE_TYPES.contains(leaveType)) leaveType = "年假";
            if (year == null || year <= 0) year = LocalDate.now().getYear();
            if (queryType == null || queryType.isBlank()) queryType = "balance";

            LeaveProfile profile = getOrGenerateProfile(employeeName, year);

            String result = "detail".equals(queryType)
                    ? buildDetailResult(profile, leaveType)
                    : buildBalanceResult(profile, leaveType);

            log.info("MCP 工具调用完成, toolId={}, queryType={}, employeeName={}, leaveType={}, year={}, elapsed={}ms",
                    TOOL_ID, queryType, employeeName, leaveType, year, System.currentTimeMillis() - startMs);
            return successResult(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return errorResult("查询失败: " + e.getMessage());
        }
    }

    private String buildBalanceResult(LeaveProfile profile, String leaveType) {
        return switch (leaveType) {
            case "调休" -> buildCompensatoryBalance(profile);
            case "病假", "事假" -> buildPlainBalance(profile, leaveType);
            default -> buildAnnualBalance(profile);
        };
    }

    private String buildAnnualBalance(LeaveProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s %d 年年假余额】%n%n", profile.employeeName, profile.year));
        sb.append(String.format("入职日期: %s（工龄 %s）%n", profile.hireDate, profile.serviceLength()));
        sb.append(String.format("全年额度: %s 天，%d-01-01 一次性发放%n", days(profile.quota), profile.year));
        sb.append(String.format("上年结转: %s 天，截止 %s%n", days(profile.carriedIn), profile.carryDeadline()));
        sb.append(String.format("  结转部分已使用: %s 天%n", days(profile.carriedUsed())));
        sb.append(String.format("  结转部分逾期清零: %s 天%n", days(profile.carriedExpired())));
        sb.append(String.format("本年已休: %s 天，共 %d 次%n",
                days(profile.usedDays("年假")), profile.records("年假").size()));
        sb.append(String.format("当前可用: %s 天%n", days(profile.annualAvailable())));

        LeaveRecord last = profile.lastRecord("年假");
        if (last != null) {
            sb.append(String.format("最近一次年假: %s 至 %s，%s 天%n", last.startDate, last.endDate, days(last.days)));
        }
        return sb.toString().trim();
    }

    private String buildCompensatoryBalance(LeaveProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s %d 年调休余额】%n%n", profile.employeeName, profile.year));
        sb.append(String.format("当前可用: %s 天%n", days(profile.compensatoryAvailable())));
        sb.append(String.format("本年已休: %s 天%n", days(profile.usedDays("调休"))));
        sb.append(String.format("逾期清零: %s 天%n", days(profile.compensatoryExpired())));

        List<CompensatoryGrant> valid = profile.validGrants();
        sb.append("\n【调休来源】\n");
        if (valid.isEmpty()) {
            sb.append("  当前无未到期的调休额度\n");
        } else {
            valid.forEach(g -> sb.append(String.format("  %s 加班 %s 天，%s 到期，剩余 %s 天%n",
                    g.overtimeDate, days(g.grantedDays), g.expireDate, days(g.remainingDays))));
        }
        return sb.toString().trim();
    }

    private String buildPlainBalance(LeaveProfile profile, String leaveType) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s %d 年%s使用情况】%n%n", profile.employeeName, profile.year, leaveType));
        sb.append(String.format("本年已休: %s 天，共 %d 次%n",
                days(profile.usedDays(leaveType)), profile.records(leaveType).size()));

        LeaveRecord last = profile.lastRecord(leaveType);
        if (last != null) {
            sb.append(String.format("最近一次: %s 至 %s，%s 天%n", last.startDate, last.endDate, days(last.days)));
        }
        sb.append(leaveType).append("不设年度额度，按实际发生逐次登记");
        return sb.toString().trim();
    }

    private String buildDetailResult(LeaveProfile profile, String leaveType) {
        List<LeaveRecord> records = profile.records(leaveType);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s %d 年%s请假明细】%n%n", profile.employeeName, profile.year, leaveType));
        if (records.isEmpty()) {
            sb.append("本年度暂无").append(leaveType).append("记录");
            return sb.toString();
        }
        sb.append(String.format("共 %d 条，合计 %s 天：%n%n", records.size(), days(profile.usedDays(leaveType))));
        for (int i = 0; i < records.size(); i++) {
            LeaveRecord r = records.get(i);
            sb.append(String.format("%d. %s 至 %s | %s 天 | %s | 审批人: %s%n",
                    i + 1, r.startDate, r.endDate, days(r.days), r.status, r.approver));
        }
        return sb.toString().trim();
    }

    private LeaveProfile getOrGenerateProfile(String employeeName, int year) {
        String key = employeeName + "_" + year + "_" + LocalDate.now();
        if (cachedProfile != null && key.equals(cacheKey)) return cachedProfile;
        cachedProfile = generateProfile(employeeName, year);
        cacheKey = key;
        return cachedProfile;
    }

    private LeaveProfile generateProfile(String employeeName, int year) {
        Random random = new Random(employeeName.hashCode() * 31L + year);

        LeaveProfile profile = new LeaveProfile();
        profile.employeeName = employeeName;
        profile.year = year;
        profile.serviceMonths = 24 + random.nextInt(72);
        profile.hireDate = LocalDate.now().minusMonths(profile.serviceMonths).format(DateTimeFormatter.ISO_LOCAL_DATE);
        profile.quota = Math.min(15, 10 + (profile.serviceMonths / 12 - 1) / 3);
        profile.carriedIn = 1 + random.nextInt(5);

        int lastMonth = year >= LocalDate.now().getYear() ? LocalDate.now().getMonthValue() : 12;
        double annualBudget = profile.quota + profile.carriedIn;
        profile.leaveRecords.addAll(generateRecords(random, year, lastMonth, "年假",
                roundHalf(annualBudget * (0.35 + random.nextDouble() * 0.35))));
        profile.leaveRecords.addAll(generateRecords(random, year, lastMonth, "病假",
                roundHalf(random.nextDouble() * 4)));
        profile.leaveRecords.addAll(generateRecords(random, year, lastMonth, "事假",
                roundHalf(random.nextDouble() * 3)));

        profile.grants.addAll(generateGrants(random, year, lastMonth));
        double compensatoryEarned = profile.grants.stream().mapToDouble(g -> g.grantedDays).sum();
        profile.leaveRecords.addAll(generateRecords(random, year, lastMonth, "调休",
                roundHalf(compensatoryEarned * random.nextDouble() * 0.6)));
        profile.consumeGrants();

        profile.leaveRecords.sort((a, b) -> a.startDate.compareTo(b.startDate));
        return profile;
    }

    private List<LeaveRecord> generateRecords(Random random, int year, int lastMonth, String type, double totalDays) {
        List<LeaveRecord> records = new ArrayList<>();
        double remaining = totalDays;
        int month = 1;
        while (remaining >= 0.5 && month <= lastMonth) {
            double take = Math.min(remaining, 0.5 + 0.5 * random.nextInt(6));
            LocalDate start = LocalDate.of(year, month, 1).plusDays(random.nextInt(24));
            LeaveRecord record = new LeaveRecord();
            record.type = type;
            record.days = take;
            record.startDate = start.format(DateTimeFormatter.ISO_LOCAL_DATE);
            record.endDate = start.plusDays((long) Math.max(0, Math.ceil(take) - 1)).format(DateTimeFormatter.ISO_LOCAL_DATE);
            record.status = "已批准";
            record.approver = APPROVERS.get(random.nextInt(APPROVERS.size()));
            records.add(record);
            remaining -= take;
            month += 1 + random.nextInt(3);
        }
        return records;
    }

    private List<CompensatoryGrant> generateGrants(Random random, int year, int lastMonth) {
        List<CompensatoryGrant> grants = new ArrayList<>();
        int count = 2 + random.nextInt(3);
        for (int i = 0; i < count; i++) {
            LocalDate overtime = LocalDate.of(year, 1 + random.nextInt(lastMonth), 1).plusDays(random.nextInt(27));
            CompensatoryGrant grant = new CompensatoryGrant();
            grant.overtimeDate = overtime.format(DateTimeFormatter.ISO_LOCAL_DATE);
            grant.expireDate = overtime.plusDays(90).format(DateTimeFormatter.ISO_LOCAL_DATE);
            grant.grantedDays = 0.5 + 0.5 * random.nextInt(4);
            grant.remainingDays = grant.grantedDays;
            grants.add(grant);
        }
        grants.sort((a, b) -> a.overtimeDate.compareTo(b.overtimeDate));
        return grants;
    }

    private static double roundHalf(double value) {
        return Math.round(value * 2) / 2.0;
    }

    private static String days(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.format("%.1f", value);
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : null;
    }

    private static Integer intArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val != null) {
            try {
                return Integer.parseInt(val.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
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

    private static class LeaveProfile {
        String employeeName;
        int year;
        int serviceMonths;
        String hireDate;
        int quota;
        int carriedIn;
        final List<LeaveRecord> leaveRecords = new ArrayList<>();
        final List<CompensatoryGrant> grants = new ArrayList<>();

        String serviceLength() {
            int years = serviceMonths / 12;
            int rest = serviceMonths % 12;
            return rest == 0 ? years + " 年" : years + " 年 " + rest + " 个月";
        }

        String carryDeadline() {
            return LocalDate.of(year, 6, 30).format(DateTimeFormatter.ISO_LOCAL_DATE);
        }

        List<LeaveRecord> records(String type) {
            return leaveRecords.stream().filter(r -> r.type.equals(type)).toList();
        }

        LeaveRecord lastRecord(String type) {
            List<LeaveRecord> records = records(type);
            return records.isEmpty() ? null : records.get(records.size() - 1);
        }

        double usedDays(String type) {
            return records(type).stream().mapToDouble(r -> r.days).sum();
        }

        double carriedUsed() {
            String deadline = carryDeadline();
            double usedBeforeDeadline = records("年假").stream()
                    .filter(r -> r.endDate.compareTo(deadline) <= 0)
                    .mapToDouble(r -> r.days).sum();
            return Math.min(carriedIn, usedBeforeDeadline);
        }

        boolean carryDeadlinePassed() {
            return LocalDate.now().isAfter(LocalDate.of(year, 6, 30));
        }

        double carriedExpired() {
            return carryDeadlinePassed() ? carriedIn - carriedUsed() : 0;
        }

        double annualAvailable() {
            double carriedAvailable = carryDeadlinePassed() ? 0 : carriedIn - carriedUsed();
            return quota - (usedDays("年假") - carriedUsed()) + carriedAvailable;
        }

        void consumeGrants() {
            double remaining = usedDays("调休");
            for (CompensatoryGrant grant : grants) {
                if (remaining <= 0) break;
                double take = Math.min(remaining, grant.remainingDays);
                grant.remainingDays -= take;
                remaining -= take;
            }
        }

        List<CompensatoryGrant> validGrants() {
            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            return grants.stream()
                    .filter(g -> g.remainingDays > 0 && g.expireDate.compareTo(today) >= 0)
                    .toList();
        }

        double compensatoryAvailable() {
            return validGrants().stream().mapToDouble(g -> g.remainingDays).sum();
        }

        double compensatoryExpired() {
            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            return grants.stream()
                    .filter(g -> g.remainingDays > 0 && g.expireDate.compareTo(today) < 0)
                    .mapToDouble(g -> g.remainingDays).sum();
        }
    }

    private static class LeaveRecord {
        String type;
        String startDate;
        String endDate;
        double days;
        String status;
        String approver;
    }

    private static class CompensatoryGrant {
        String overtimeDate;
        String expireDate;
        double grantedDays;
        double remainingDays;
    }
}

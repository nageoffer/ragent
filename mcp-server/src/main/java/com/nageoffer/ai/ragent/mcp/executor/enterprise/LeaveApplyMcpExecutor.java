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

package com.nageoffer.ai.ragent.mcp.executor.enterprise;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.ragent.mcp.executor.McpToolResults;
import com.nageoffer.ai.ragent.mcp.executor.McpToolSchema;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;

/**
 * 请假申请提交，写操作示例
 * 执行前确认卡由意图树节点的开关触发，这里声明的 readOnlyHint=false 是漏勾时的兜底
 */
@Slf4j
@Component
public class LeaveApplyMcpExecutor {

    private static final String TOOL_ID = "leave_submit";

    /**
     * MCP 进程拿不到登录态，示例里固定为当前员工，不开放给模型指定
     */
    private static final String CURRENT_EMPLOYEE = "张三";

    private static final List<String> LEAVE_TYPES = List.of("年假", "调休", "病假", "事假");

    private static final String APPROVER = "李经理";

    /**
     * 单次请假的自然日上限，拦住模型把「休一阵子」放大成整年
     */
    private static final int MAX_SPAN_DAYS = 30;

    @Bean
    public McpServerFeatures.SyncToolSpecification leaveSubmitToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("leaveType", "假期类型：年假、调休、病假、事假")
                        .title("假期类型")
                        .options(LEAVE_TYPES))
                .required(string("startDate", "请假开始日期，格式 yyyy-MM-dd，如 2026-09-07")
                        .title("开始日期"))
                .required(string("endDate", "请假结束日期，格式 yyyy-MM-dd，当天请假填与开始日期相同的值")
                        .title("结束日期"))
                .required(string("reason", "请假事由，据实填写用户说明的原因，不要代为编造")
                        .title("请假事由"))
                .build();

        return Tool.builder()
                .name(TOOL_ID)
                .description("为当前登录员工提交请假申请，提交后进入直属经理审批流程。"
                        + "本工具会产生真实业务副作用，日期与事由必须来自用户明确说明，"
                        + "建议先用假期余额查询确认额度是否充足")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.WRITE)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String leaveType = MapUtil.getStr(args, "leaveType");
            String startDate = MapUtil.getStr(args, "startDate");
            String endDate = MapUtil.getStr(args, "endDate");
            String reason = MapUtil.getStr(args, "reason");

            LocalDate start = McpToolResults.parseDate(startDate);
            LocalDate end = McpToolResults.parseDate(endDate);

            String rejection = validate(leaveType, start, end, reason);
            if (rejection != null) {
                log.info("MCP 工具调用被拒, toolId={}, reason={}, elapsed={}ms",
                        TOOL_ID, rejection, System.currentTimeMillis() - startMs);
                return McpToolResults.error(rejection);
            }

            assert start != null;
            long days = ChronoUnit.DAYS.between(start, end) + 1;
            String applicationNo = nextApplicationNo();

            log.info("提交请假申请, toolId={}, applicationNo={}, employee={}, leaveType={}, "
                            + "startDate={}, endDate={}, days={}, reason={}, elapsed={}ms",
                    TOOL_ID, applicationNo, CURRENT_EMPLOYEE, leaveType, startDate, endDate, days, reason,
                    System.currentTimeMillis() - startMs);
            return McpToolResults.success(buildReceipt(applicationNo, leaveType, start, end, days, reason));
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("提交", e);
        }
    }

    /**
     * 校验不通过返回给模型的中文说明，让它回头问用户而不是自行补全
     */
    private String validate(String leaveType, LocalDate start, LocalDate end, String reason) {
        if (leaveType == null || !LEAVE_TYPES.contains(leaveType)) {
            return "假期类型缺失或不受支持，请确认后重新提交，可选值：" + String.join("、", LEAVE_TYPES);
        }
        if (StrUtil.isBlank(reason)) {
            return "请假事由为必填项，请向用户确认后再提交";
        }
        if (start == null || end == null) {
            return "请假日期缺失或格式不正确，需要 yyyy-MM-dd 格式的开始日期与结束日期";
        }
        if (end.isBefore(start)) {
            return "结束日期早于开始日期，请确认后重新提交";
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_SPAN_DAYS) {
            return String.format("单次请假跨度 %d 天已超过 %d 天上限，请向用户确认实际起止日期", days, MAX_SPAN_DAYS);
        }
        return null;
    }

    private String buildReceipt(String applicationNo, String leaveType,
                                LocalDate start, LocalDate end, long days, String reason) {
        return "【请假申请已提交】\n\n" +
                String.format("申请单号: %s%n", applicationNo) +
                String.format("申请人: %s%n", CURRENT_EMPLOYEE) +
                String.format("假期类型: %s%n", leaveType) +
                String.format("请假区间: %s 至 %s，共 %d 个自然日%n", start, end, days) +
                String.format("请假事由: %s%n", reason) +
                String.format("当前状态: 待审批，审批人 %s%n", APPROVER) +
                "预计 1 个工作日内出审批结果，可在办公工作台的我的申请中查看进度";
    }

    private String nextApplicationNo() {
        return String.format("LV-%s-%04d", LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE),
                RandomUtil.randomInt(10000));
    }
}

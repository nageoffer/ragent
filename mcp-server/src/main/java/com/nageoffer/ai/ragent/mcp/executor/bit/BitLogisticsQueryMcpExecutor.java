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
import com.nageoffer.ai.ragent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.ragent.mcp.dao.entity.LogisticsTraceDO;
import com.nageoffer.ai.ragent.mcp.dao.entity.OrderDO;
import com.nageoffer.ai.ragent.mcp.dao.mapper.LogisticsTraceMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.OrderMapper;
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

import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;

/**
 * 物流轨迹查询，入参只收运单号
 * <p>
 * 不开放按订单号查：运单号得先从订单查询里取，这一步依赖是「快递到哪了」这条路的形状本身
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitLogisticsQueryMcpExecutor {

    private static final String TOOL_ID = "query_logistics";

    private final OrderMapper orderMapper;
    private final LogisticsTraceMapper logisticsTraceMapper;

    @Bean
    public McpServerFeatures.SyncToolSpecification queryLogisticsToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("trackingNo", "运单号，如 BIT9100000233，从订单查询的结果里取"))
                .build();

        return Tool.builder()
                .name(TOOL_ID)
                .description("按运单号查询物流轨迹，返回对应订单号、订单状态与全部轨迹节点，由新到旧。"
                        + "运单号先用订单查询取到再传进来，不要凭用户口述或自行拼接")
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
            String trackingNo = StrUtil.trimToNull(MapUtil.getStr(args, "trackingNo"));
            if (trackingNo == null) {
                return McpToolResults.error("请提供运单号，可先用订单查询拿到");
            }

            // 归属靠订单表定，不能只凭运单号取轨迹——运单号是猜得出来的
            OrderDO order = orderMapper.selectByTrackingNo(trackingNo, userId);

            log.info("MCP 工具调用完成, toolId={}, trackingNo={}, matched={}, elapsed={}ms",
                    TOOL_ID, trackingNo, order == null ? 0 : 1, System.currentTimeMillis() - startMs);
            return order == null
                    ? BitToolSupport.notFound("运单 " + trackingNo + " 的物流信息")
                    : McpToolResults.success(buildResult(order, trackingNo));
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("物流查询", e);
        }
    }

    private String buildResult(OrderDO order, String trackingNo) {
        List<LogisticsTraceDO> traces = logisticsTraceMapper.selectByTrackingNo(trackingNo);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【运单 %s】对应订单 %s，订单状态 %s%n", trackingNo, order.getOrderNo(), order.getStatus()));
        sb.append(String.format("发货时间: %s%n", BitToolSupport.dateTime(order.getShipTime())));
        if (order.getReceiveTime() != null) {
            sb.append(String.format("签收时间: %s%n", BitToolSupport.dateTime(order.getReceiveTime())));
        }

        if (traces.isEmpty()) {
            sb.append("\n暂无轨迹节点，包裹可能刚揽收");
            return sb.toString();
        }
        LogisticsTraceDO latest = traces.get(0);
        sb.append(String.format("%n最新: %s %s %s%n", BitToolSupport.dateTime(latest.getTraceTime()),
                latest.getLocation(), latest.getDescription()));

        sb.append(String.format("%n全部轨迹（共 %d 条，由新到旧）:%n", traces.size()));
        for (int i = 0; i < traces.size(); i++) {
            LogisticsTraceDO trace = traces.get(i);
            sb.append(String.format("%d. %s | %s | %s%n", i + 1, BitToolSupport.dateTime(trace.getTraceTime()),
                    trace.getLocation(), trace.getDescription()));
        }
        return sb.toString().trim();
    }


}

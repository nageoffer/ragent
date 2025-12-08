package com.nageoffer.ai.ragent.rag.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * MCP 服务协调器
 * <p>
 * 提供 MCP 工具调用的核心逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor

public class MCPServiceOrchestrator implements MCPService {

    private final MCPToolRegistry toolRegistry;

    @Override
    public MCPResponse execute(MCPRequest request) {
        if (request == null || request.getToolId() == null) {
            return MCPResponse.error(null, "INVALID_REQUEST", "请求参数无效");
        }

        String toolId = request.getToolId();
        long startTime = System.currentTimeMillis();

        log.info("[MCPService] 开始执行工具调用, toolId={}, userId={}", toolId, request.getUserId());

        Optional<MCPToolExecutor> executorOpt = toolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            log.warn("[MCPService] 工具不存在, toolId={}", toolId);
            return MCPResponse.error(toolId, "TOOL_NOT_FOUND", "工具不存在: " + toolId);
        }

        MCPToolExecutor executor = executorOpt.get();
        MCPTool tool = executor.getToolDefinition();

        // 检查是否需要用户身份
        if (tool.isRequireUserId() && (request.getUserId() == null || request.getUserId().isBlank())) {
            log.warn("[MCPService] 工具需要用户身份但未提供, toolId={}", toolId);
            return MCPResponse.error(toolId, "USER_ID_REQUIRED", "该工具需要用户身份信息");
        }

        try {
            MCPResponse response = executor.execute(request);
            long costMs = System.currentTimeMillis() - startTime;
            response.setCostMs(costMs);

            log.info("[MCPService] 工具调用完成, toolId={}, success={}, costMs={}",
                    toolId, response.isSuccess(), costMs);

            return response;
        } catch (Exception e) {
            log.error("[MCPService] 工具调用异常, toolId={}", toolId, e);
            MCPResponse errorResponse = MCPResponse.error(toolId, "EXECUTION_ERROR", "工具调用异常: " + e.getMessage());
            errorResponse.setCostMs(System.currentTimeMillis() - startTime);
            return errorResponse;
        }
    }

    @Override
    public List<MCPResponse> executeBatch(List<MCPRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }

        log.info("[MCPService] 批量执行工具调用, count={}", requests.size());

        // 并行执行所有请求
        List<CompletableFuture<MCPResponse>> futures = requests.stream()
                .map(request -> CompletableFuture.supplyAsync(() -> execute(request)))
                .toList();

        // 等待所有任务完成并收集结果
        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
    }

    @Override
    public List<MCPTool> listAvailableTools() {
        return toolRegistry.listAllTools();
    }

    @Override
    public boolean isToolAvailable(String toolId) {
        return toolRegistry.contains(toolId);
    }

    @Override
    public String buildToolsDescription() {
        List<MCPTool> tools = listAvailableTools();
        if (tools.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【可用工具列表】\n\n");

        for (MCPTool tool : tools) {
            sb.append("工具名称: ").append(tool.getName()).append("\n");
            sb.append("工具ID: ").append(tool.getToolId()).append("\n");
            sb.append("功能描述: ").append(tool.getDescription()).append("\n");

            if (tool.getExamples() != null && !tool.getExamples().isEmpty()) {
                sb.append("示例问题: ").append(String.join(" / ", tool.getExamples())).append("\n");
            }

            if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                sb.append("参数:\n");
                tool.getParameters().forEach((name, def) -> {
                    sb.append("  - ").append(name);
                    if (def.isRequired()) {
                        sb.append(" (必填)");
                    }
                    sb.append(": ").append(def.getDescription()).append("\n");
                });
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    @Override
    public String mergeResponsesToText(List<MCPResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return "";
        }

        List<String> successResults = new ArrayList<>();
        List<String> errorResults = new ArrayList<>();

        for (MCPResponse response : responses) {
            if (response.isSuccess() && response.getTextResult() != null) {
                successResults.add(response.getTextResult());
            } else if (!response.isSuccess()) {
                errorResults.add(String.format("工具 %s 调用失败: %s",
                        response.getToolId(), response.getErrorMessage()));
            }
        }

        StringBuilder sb = new StringBuilder();

        if (!successResults.isEmpty()) {
            sb.append("【实时数据查询结果】\n\n");
            for (String result : successResults) {
                sb.append(result).append("\n\n");
            }
        }

        if (!errorResults.isEmpty()) {
            sb.append("【部分查询失败】\n");
            for (String error : errorResults) {
                sb.append("- ").append(error).append("\n");
            }
        }

        return sb.toString().trim();
    }
}

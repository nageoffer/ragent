package com.nageoffer.ai.ragent.rag.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 服务单元测试
 */
@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class MCPServiceTests {

    private final MCPToolRegistry toolRegistry;
    private final MCPService mcpService;

    /**
     * 测试工具注册表自动发现
     */
    @Test
    public void testToolRegistryAutoDiscovery() {
        System.out.println("==================================================");
        System.out.println("[MCPServiceTests] 测试工具注册表自动发现");
        System.out.println("--------------------------------------------------");

        List<MCPTool> tools = toolRegistry.listAllTools();
        System.out.println("已注册工具数量: " + tools.size());

        tools.forEach(tool -> {
            System.out.println();
            System.out.println("工具ID: " + tool.getToolId());
            System.out.println("工具名称: " + tool.getName());
            System.out.println("工具描述: " + tool.getDescription());
            if (tool.getExamples() != null) {
                System.out.println("示例问题: " + String.join(" / ", tool.getExamples()));
            }
        });

        System.out.println("==================================================\n");

        assertTrue(tools.size() >= 1, "应至少注册 1 个示例工具");
        assertTrue(toolRegistry.contains("sales_query"), "应包含销售数据查询工具");
    }

    /**
     * 测试销售汇总查询
     */
    @Test
    public void testSalesSummaryQuery() {
        System.out.println("==================================================");
        System.out.println("[MCPServiceTests] 测试销售汇总查询");
        System.out.println("--------------------------------------------------");

        MCPRequest request = MCPRequest.builder()
                .toolId("sales_query")
                .userQuestion("华东区这个月销售额多少？")
                .build();
        request.addParameter("region", "华东");
        request.addParameter("period", "本月");
        request.addParameter("queryType", "summary");

        long start = System.nanoTime();
        MCPResponse response = mcpService.execute(request);
        long costMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("请求参数: " + request);
        System.out.println("响应成功: " + response.isSuccess());
        System.out.println("响应数据: " + response.getData());
        System.out.println("文本结果:\n" + response.getTextResult());
        System.out.println("耗时: " + costMs + " ms");
        System.out.println("==================================================");

        assertTrue(response.isSuccess(), "销售汇总查询应成功");
        assertNotNull(response.getTextResult(), "应返回文本结果");
        assertNotNull(response.getData(), "应返回结构化数据");
        assertNotNull(response.getData().get("totalAmount"), "应返回总销售额");
    }

    /**
     * 测试销售排名查询
     */
    @Test
    public void testSalesRankingQuery() {
        System.out.println("==================================================");
        System.out.println("[MCPServiceTests] 测试销售排名查询");
        System.out.println("--------------------------------------------------");

        MCPRequest request = MCPRequest.builder()
                .toolId("sales_query")
                .userQuestion("本月销售排名前五是谁？")
                .build();
        request.addParameter("period", "本月");
        request.addParameter("queryType", "ranking");
        request.addParameter("limit", 5);

        long start = System.nanoTime();
        MCPResponse response = mcpService.execute(request);
        long costMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("请求参数: " + request);
        System.out.println("响应成功: " + response.isSuccess());
        System.out.println("响应数据: " + response.getData());
        System.out.println("文本结果:\n" + response.getTextResult());
        System.out.println("耗时: " + costMs + " ms");
        System.out.println("==================================================");

        assertTrue(response.isSuccess(), "销售排名查询应成功");
        assertNotNull(response.getTextResult(), "应返回文本结果");
        assertNotNull(response.getData().get("ranking"), "应返回排名数据");
    }

    /**
     * 测试销售明细查询
     */
    @Test
    public void testSalesDetailQuery() {
        System.out.println("==================================================");
        System.out.println("[MCPServiceTests] 测试销售明细查询");
        System.out.println("--------------------------------------------------");

        MCPRequest request = MCPRequest.builder()
                .toolId("sales_query")
                .userQuestion("企业版这个月的销售明细")
                .build();
        request.addParameter("product", "企业版");
        request.addParameter("queryType", "detail");
        request.addParameter("limit", 5);

        long start = System.nanoTime();
        MCPResponse response = mcpService.execute(request);
        long costMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("请求参数: " + request);
        System.out.println("响应成功: " + response.isSuccess());
        System.out.println("响应数据: " + response.getData());
        System.out.println("文本结果:\n" + response.getTextResult());
        System.out.println("耗时: " + costMs + " ms");
        System.out.println("==================================================");

        assertTrue(response.isSuccess(), "销售明细查询应成功");
        assertNotNull(response.getData().get("records"), "应返回销售记录列表");
    }

    /**
     * 测试销售趋势查询
     */
    @Test
    public void testSalesTrendQuery() {
        System.out.println("==================================================");
        System.out.println("[MCPServiceTests] 测试销售趋势查询");
        System.out.println("--------------------------------------------------");

        MCPRequest request = MCPRequest.builder()
                .toolId("sales_query")
                .userQuestion("本月销售趋势如何？")
                .build();
        request.addParameter("queryType", "trend");

        MCPResponse response = mcpService.execute(request);

        System.out.println("响应成功: " + response.isSuccess());
        System.out.println("文本结果:\n" + response.getTextResult());
        System.out.println("==================================================");

        assertTrue(response.isSuccess(), "销售趋势查询应成功");
        assertNotNull(response.getData().get("byWeek"), "应返回按周趋势数据");
    }

    /**
     * 测试按销售人员查询
     */
    @Test
    public void testSalesPersonQuery() {
        System.out.println("==================================================");
        System.out.println("[MCPServiceTests] 测试按销售人员查询");
        System.out.println("--------------------------------------------------");

        MCPRequest request = MCPRequest.builder()
                .toolId("sales_query")
                .userQuestion("张三这个月业绩怎么样？")
                .build();
        request.addParameter("salesPerson", "张三");
        request.addParameter("queryType", "summary");

        MCPResponse response = mcpService.execute(request);

        System.out.println("响应成功: " + response.isSuccess());
        System.out.println("文本结果:\n" + response.getTextResult());
        System.out.println("==================================================");

        assertTrue(response.isSuccess(), "按销售人员查询应成功");
    }
}

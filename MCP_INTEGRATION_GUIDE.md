# 谷粒商城 MCP 工具集成实现方案

## 项目背景

- **谷粒商城 (guli2)**: 业务系统，提供商品查询、订单管理等电商功能
- **Ragent**: AI 检索与 Agent Workflow 平台，支持 RAG、意图识别、MCP 工具调用
- **目标**: 通过 MCP 协议将两者连接，让用户可以通过 Ragent 查询商品信息

## 已完成的工作

### 1. MCP Server 侧 - 商品查询工具 (✅ 已完成)

已在 `/workspace/mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/executor/` 下创建两个工具执行器：

#### 1.1 商品详情查询工具 (`ProductDetailMcpExecutor.java`)
- **Tool ID**: `product_detail_query`
- **功能**: 查询商品详细信息（名称、品牌、价格、描述、颜色等）
- **参数**: 
  - `productId` (必填): 商品 ID
- **模拟数据**: 包含 iPhone 15 Pro、华为 Mate 60 Pro、小米 14 Ultra、MacBook Pro 等商品

#### 1.2 商品库存查询工具 (`ProductStockMcpExecutor.java`)
- **Tool ID**: `product_stock_query`
- **功能**: 查询商品 SKU 库存信息
- **参数**:
  - `productId` (必填): 商品 ID
  - `skuId` (可选): SKU ID，不提供则查询所有 SKU
- **模拟数据**: 每个商品有多个 SKU（不同颜色、规格组合）

### 2. 需要补充的工作

#### 2.1 在 IntentTreeFactory 中添加商品相关意图节点

**文件位置**: `/workspace/rag/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentTreeFactory.java`

需要在 `buildIntentTree()` 方法中添加以下意图树结构：

```java
// ========== 5. 谷粒商城 - 商品信息查询 ==========
IntentNode guliEcommerce = IntentNode.builder()
        .id("guli-ecommerce")
        .name("谷粒商城")
        .level(DOMAIN)
        .kind(IntentKind.MCP)
        .build();

// 商品详情查询
IntentNode productDetail = IntentNode.builder()
        .id("guli-product-detail")
        .name("商品详情查询")
        .level(CATEGORY)
        .parentId(guliEcommerce.getId())
        .kind(IntentKind.MCP)
        .mcpToolId("product_detail_query")
        .description("查询商品的详细信息，包括商品名称、品牌、分类、价格、描述、颜色等")
        .examples(List.of(
                "iPhone 15 Pro 的详情",
                "商品 ID 为 1 的商品信息",
                "华为 Mate 60 Pro 有什么特点"
        ))
        .paramPromptTemplate(MCP_PRODUCT_DETAIL_PARAMETER_EXTRACT_PROMPT)
        .promptTemplate(MCP_PRODUCT_DETAIL_PROMPT_TEMPLATE)
        .build();

// 商品库存查询
IntentNode productStock = IntentNode.builder()
        .id("guli-product-stock")
        .name("商品库存查询")
        .level(CATEGORY)
        .parentId(guliEcommerce.getId())
        .kind(IntentKind.MCP)
        .mcpToolId("product_stock_query")
        .description("查询商品的库存情况，支持按商品 ID 或 SKU ID 查询，返回库存数量、价格、规格等")
        .examples(List.of(
                "iPhone 15 Pro 还有货吗",
                "SKU 101 的库存是多少",
                "商品 1 现在还有货吗"
        ))
        .paramPromptTemplate(MCP_PRODUCT_STOCK_PARAMETER_EXTRACT_PROMPT)
        .promptTemplate(MCP_PRODUCT_STOCK_PROMPT_TEMPLATE)
        .build();

guliEcommerce.setChildren(List.of(productDetail, productStock));
roots.add(guliEcommerce);
```

#### 2.2 添加参数提取 Prompt 模板

在 `IntentTreeFactory.java` 文件末尾添加以下常量：

```java
// ========== 谷粒商城 MCP 工具 Prompts ==========

/**
 * 商品详情查询 - 参数提取 Prompt
 */
public static final String MCP_PRODUCT_DETAIL_PARAMETER_EXTRACT_PROMPT = """
        Hello，你是一个高度专业且严谨的【商品详情查询参数提取器】。
        
        你的唯一任务是：严格按照提供的【工具定义】和【参数列表】的约束，从【用户问题】中提取所有必要的参数，并以 JSON 格式输出。
        
        ### 核心提取逻辑
        
        1. **数据源限定**：只使用【用户问题】中的信息作为提取来源。
        2. **参数范围限定**：只提取 <parameters> 标签内定义的参数，**禁止**添加任何工具定义中不存在的额外字段。
        3. **必填参数处理**：
           - `productId` 是必填参数，如果用户问题中无法找到明确的商品 ID：
             - 尝试从上下文中推断商品 ID（如提到"iPhone 15 Pro"对应 ID 1）
             - 如果确实无法推断，将该参数的值输出为 **null**
        
        ### 商品 ID 映射规则
        
        如果用户提到以下商品名称，请映射到对应的商品 ID：
        - "iPhone 15 Pro"、"苹果 15 Pro" → productId: 1
        - "华为 Mate 60 Pro"、"Mate 60 Pro" → productId: 2
        - "小米 14 Ultra"、"Mi 14 Ultra" → productId: 3
        - "MacBook Pro 14"、"苹果笔记本" → productId: 10
        - "ThinkPad X1 Carbon"、"联想 X1" → productId: 11
        - "iPad Pro 12.9"、"苹果平板" → productId: 20
        - "华为 MatePad Pro"、"华为平板" → productId: 21
        
        ### 输入数据与输出格式
        
        #### 【工具定义】
        <tool_definition>
        %s
        </tool_definition>
        
        #### 【用户问题】
        <user_query>
        %s
        </user_query>
        
        #### 【输出格式（JSON Object Only）】
        
        {"productId": 商品 ID 数字或 null}
        
        """;

/**
 * 商品详情查询 - 结果展示 Prompt
 */
private static final String MCP_PRODUCT_DETAIL_PROMPT_TEMPLATE = """
        Hello，你是专业的电商购物助手。系统已调用内部工具获取到了最新的【商品详情数据】。
        你的任务是将这些结构化数据转化为**商业化、易读的自然语言**回复，帮助用户了解商品信息。
        
        【核心处理规则】
        1. **直接回答**：开门见山地介绍商品，不要使用"根据数据显示"这类废话作为开头。
        2. **突出卖点**：重点强调商品的核心特性、优势和使用场景。
        3. **格式化输出**：
           - 使用清晰的段落和分点展示商品信息
           - 对价格、库存等关键信息进行加粗（**Bold**）处理
           - 如果有多个颜色可选，清晰列出所有选项
        
        【异常与边界处理】
        1. **数据为空**：如果【商品详情数据】为空或 null，请礼貌地告知用户未找到该商品信息。
        2. **库存紧张**：如果库存较少（<=30 件），可以善意提醒用户尽早购买。
        3. **多意图部分匹配**：如果用户同时询问了多个商品，而数据只能回答其中部分：
           - **先回答能回答的部分**，按正常格式输出商品信息
           - **再说明无法回答的部分**，例如："关于『XX 商品』，当前未查询到相关信息。"
        
        【禁止事项】
        - 严禁虚构商品信息或价格
        - 严禁透漏你正在解析数据的过程
        
        {{INTENT_RULES}}
        
        【商品详情数据】
        %s
        
        【用户问题】
        %s
        """;

/**
 * 商品库存查询 - 参数提取 Prompt
 */
public static final String MCP_PRODUCT_STOCK_PARAMETER_EXTRACT_PROMPT = """
        Hello，你是一个高度专业且严谨的【商品库存查询参数提取器】。
        
        你的唯一任务是：严格按照提供的【工具定义】和【参数列表】的约束，从【用户问题】中提取所有必要的参数，并以 JSON 格式输出。
        
        ### 核心提取逻辑
        
        1. **数据源限定**：只使用【用户问题】中的信息作为提取来源。
        2. **参数范围限定**：只提取 <parameters> 标签内定义的参数，**禁止**添加任何工具定义中不存在的额外字段。
        3. **必填参数处理**：
           - `productId` 是必填参数，如果用户问题中无法找到明确的商品 ID：
             - 尝试从上下文中推断商品 ID（参考商品 ID 映射规则）
             - 如果确实无法推断，将该参数的值输出为 **null**
           - `skuId` 是可选参数，如果用户未提及具体 SKU，**不要包含该字段**
        
        ### 商品 ID 映射规则
        
        如果用户提到以下商品名称，请映射到对应的商品 ID：
        - "iPhone 15 Pro"、"苹果 15 Pro" → productId: 1
        - "华为 Mate 60 Pro"、"Mate 60 Pro" → productId: 2
        - "小米 14 Ultra"、"Mi 14 Ultra" → productId: 3
        - "MacBook Pro 14"、"苹果笔记本" → productId: 10
        - "ThinkPad X1 Carbon"、"联想 X1" → productId: 11
        - "iPad Pro 12.9"、"苹果平板" → productId: 20
        - "华为 MatePad Pro"、"华为平板" → productId: 21
        
        ### SKU ID 识别
        
        如果用户明确提到 SKU ID（如"SKU 101"、"编号 101"），请提取为 skuId 参数。
        
        ### 输入数据与输出格式
        
        #### 【工具定义】
        <tool_definition>
        %s
        </tool_definition>
        
        #### 【用户问题】
        <user_query>
        %s
        </user_query>
        
        #### 【输出格式（JSON Object Only）】
        
        {"productId": 商品 ID 数字或 null, "skuId": SKU ID 数字（如有）}
        
        注意：如果 skuId 未提及，不要在 JSON 中包含该字段。
        
        """;

/**
 * 商品库存查询 - 结果展示 Prompt
 */
private static final String MCP_PRODUCT_STOCK_PROMPT_TEMPLATE = """
        Hello，你是专业的电商购物助手。系统已调用内部工具获取到了最新的【商品库存数据】。
        你的任务是将这些结构化数据转化为**商业化、易读的自然语言**回复，帮助用户了解库存情况并促进购买决策。
        
        【核心处理规则】
        1. **直接回答**：开门见山地告知用户库存情况，不要使用"根据数据显示"这类废话作为开头。
        2. **库存状态解读**：
           - **库存充足**（>30 件）：让用户知道可以放心购买
           - **库存较少**（10-30 件）：善意提醒用户尽早购买
           - **库存紧张**（<=10 件）：强烈建议用户尽快下单
        3. **格式化输出**：
           - 如果是多个 SKU，使用 Markdown 表格展示各 SKU 的库存情况
           - 对库存状态、价格等关键信息进行加粗（**Bold**）处理
        
        【促销建议】
        1. 如果库存充足，可以鼓励用户"放心选购"
        2. 如果库存紧张，可以营造紧迫感"手慢无"
        3. 可以提供购买建议，如某颜色/规格性价比更高
        
        【异常与边界处理】
        1. **数据为空**：如果【商品库存数据】为空或 null，请礼貌地告知用户该商品暂无库存信息。
        2. **缺货提示**：如果某个 SKU 库存为 0，明确告知用户该规格暂时缺货。
        3. **推荐替代**：如果用户查询的 SKU 缺货，可以推荐同商品的其他有货 SKU。
        
        【禁止事项】
        - 严禁虚构库存数量
        - 严禁透漏你正在解析数据的过程
        
        {{INTENT_RULES}}
        
        【商品库存数据】
        %s
        
        【用户问题】
        %s
        """;
```

#### 2.3 数据库初始化脚本（可选）

如果需要持久化意图树配置，可以在数据库中插入以下记录：

```sql
-- 谷粒商城 - 域名节点
INSERT INTO intention_node (intent_code, parent_code, intent_name, intent_desc, level, kind, enabled, deleted)
VALUES ('guli-ecommerce', NULL, '谷粒商城', '谷粒商城商品相关信息查询', 'DOMAIN', 'MCP', 1, 0);

-- 商品详情查询 - 分类节点
INSERT INTO intention_node (intent_code, parent_code, intent_name, intent_desc, level, kind, mcp_tool_id, param_prompt_template, prompt_template, examples, enabled, deleted)
VALUES ('guli-product-detail', 'guli-ecommerce', '商品详情查询', '查询商品的详细信息，包括商品名称、品牌、分类、价格、描述、颜色等', 
'CATEGORY', 'MCP', 'product_detail_query', 
'<商品详情参数提取 PROMPT>', 
'<商品详情结果展示 PROMPT>', 
'["iPhone 15 Pro 的详情", "商品 ID 为 1 的商品信息", "华为 Mate 60 Pro 有什么特点"]', 
1, 0);

-- 商品库存查询 - 分类节点
INSERT INTO intention_node (intent_code, parent_code, intent_name, intent_desc, level, kind, mcp_tool_id, param_prompt_template, prompt_template, examples, enabled, deleted)
VALUES ('guli-product-stock', 'guli-ecommerce', '商品库存查询', '查询商品的库存情况，支持按商品 ID 或 SKU ID 查询，返回库存数量、价格、规格等', 
'CATEGORY', 'MCP', 'product_stock_query', 
'<商品库存参数提取 PROMPT>', 
'<商品库存结果展示 PROMPT>', 
'["iPhone 15 Pro 还有货吗", "SKU 101 的库存是多少", "商品 1 现在还有货吗"]', 
1, 0);
```

## 完整实现步骤

### 步骤 1: 确认 MCP Server 已创建工具 ✅

```bash
# 验证工具文件已存在
ls -la /workspace/mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/executor/Product*.java
```

### 步骤 2: 修改 IntentTreeFactory.java

编辑文件：`/workspace/rag/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentTreeFactory.java`

在 `buildIntentTree()` 方法的 `roots.add(sys);` 之前添加谷粒商城意图节点代码（参考 2.1 节）。

在文件末尾的常量区域添加 Prompts 模板（参考 2.2 节）。

### 步骤 3: 启动 MCP Server

```bash
cd /workspace/mcp-server
mvn spring-boot:run
```

验证日志中出现：
```
MCP 工具注册成功，toolId: product_detail_query
MCP 工具注册成功，toolId: product_stock_query
```

### 步骤 4: 启动 Ragent 应用

```bash
cd /workspace
mvn spring-boot:run -pl rag
```

### 步骤 5: 测试验证

使用以下测试问题进行验证：

| 测试场景 | 用户问题 | 预期命中的工具 |
|---------|---------|--------------|
| 商品详情 | "iPhone 15 Pro 的详情" | product_detail_query |
| 商品详情 | "商品 ID 为 1 的商品信息" | product_detail_query |
| 商品详情 | "华为 Mate 60 Pro 有什么特点" | product_detail_query |
| 商品库存 | "iPhone 15 Pro 还有货吗" | product_stock_query |
| 商品库存 | "SKU 101 的库存是多少" | product_stock_query |
| 商品库存 | "商品 1 现在还有货吗" | product_stock_query |

## 后续扩展建议

### 3.1 接入真实谷粒商城 API

修改 `ProductDetailMcpExecutor` 和 `ProductStockMcpExecutor`，将模拟数据替换为真实的 HTTP 调用：

```java
// 示例：调用谷粒商城真实 API
private ProductInfo fetchFromGuliApi(Long productId) {
    String url = "http://guli2-api/product/" + productId;
    return restTemplate.getForObject(url, ProductInfo.class);
}
```

### 3.2 增加更多商品相关工具

- **商品价格查询**: `product_price_query`
- **商品评价查询**: `product_review_query`
- **订单状态查询**: `order_status_query`
- **物流信息查询**: `logistics_query`
- **促销活动查询**: `promotion_query`

### 3.3 完善意图树结构

```
谷粒商城 (DOMAIN)
├── 商品信息 (CATEGORY)
│   ├── 商品详情查询 (TOPIC) -> product_detail_query
│   ├── 商品库存查询 (TOPIC) -> product_stock_query
│   ├── 商品价格查询 (TOPIC) -> product_price_query
│   └── 商品评价查询 (TOPIC) -> product_review_query
├── 订单服务 (CATEGORY)
│   ├── 订单状态查询 (TOPIC) -> order_status_query
│   └── 物流信息查询 (TOPIC) -> logistics_query
└── 促销活动 (CATEGORY)
    └── 促销活动查询 (TOPIC) -> promotion_query
```

## 关键设计原则

1. **知识库 vs 工具调用分离**
   - 知识库：回答"商品类目怎么选"、"订单流程怎么走"、"怎么退货"等流程性问题
   - 工具调用：回答"商品 X 现在价格是多少"、"库存剩余多少"等实时数据查询

2. **意图识别树的价值**
   - 通过树形结构组织业务意图，让 LLM 更精准地理解用户意图
   - 叶子节点关联具体的 MCP 工具，实现自动化工具调用

3. **参数提取的健壮性**
   - 支持商品名称到 ID 的语义映射
   - 必填参数缺失时主动追问，而非直接失败

## 总结

本方案实现了最小的 MCP 集成，核心价值点：

1. ✅ **两个 MCP 工具**: 商品详情查询 + 商品库存查询
2. ✅ **意图检索树**: 基于谷粒商城业务构建可用的意图分类
3. ✅ **参数提取**: 支持商品名称到 ID 的语义映射
4. ✅ **结果展示**: 商业化的自然语言输出，促进购买决策

下一步可以根据实际需求扩展更多工具和意图节点，逐步完善电商领域的 Agent 能力。

# 谷粒商城与 Ragent MCP 集成实现文档

## 概述

本文档描述了如何通过 MCP（Model Context Protocol）将谷粒商城（guli2）业务系统与 Ragent AI 检索系统进行真实接口调用集成，实现用户通过自然语言查询商品信息和库存信息。

## 架构设计

### 三层架构

1. **第一层：guli2 侧暴露标准接口**
   - 商品详情查询：`GET /product/display/item/{skuId}`
   - 库存查询：`GET /ware/wareSku/list?skuId={skuId}&wareId={wareId}`

2. **第二层：Ragent MCP Server 侧注册 Tool**
   - `product_detail_query` - 商品详情查询工具
   - `product_stock_query` - 商品库存查询工具

3. **第三层：意图检索树**
   - 电商领域 → 商品信息 → 商品详情查询/库存查询

## 已实现组件

### 1. 配置属性类 (GuliMcpProperties.java)

```java
package com.nageoffer.ai.ragent.mcp.executor;

@ConfigurationProperties(prefix = "guli")
public class GuliMcpProperties {
    private ProductServiceConfig product;  // 商品服务配置
    private WareServiceConfig ware;        // 仓储服务配置
}
```

**配置项：**
- `guli.product.base-url`: 商品服务基础 URL（默认：http://localhost:8080/product）
- `guli.ware.base-url`: 仓储服务基础 URL（默认：http://localhost:8080/ware）

### 2. 商品详情查询工具 (GuliProductDetailMcpExecutor.java)

**Tool ID:** `product_detail_query`

**功能：** 真实调用谷粒商城商品详情接口

**输入参数：**
```json
{
  "skuId": "integer (required) - SKU ID，例如：1, 2, 3"
}
```

**调用接口：** `GET {guli.product.base-url}/display/item/{skuId}`

**返回数据：** 格式化后的商品详情信息，包括：
- SKU ID、商品名称、商品标题
- 品牌、分类、价格、描述
- 图片数量、销售属性数量

### 3. 商品库存查询工具 (GuliProductStockMcpExecutor.java)

**Tool ID:** `product_stock_query`

**功能：** 真实调用谷粒商城库存查询接口

**输入参数：**
```json
{
  "skuId": "integer (required) - SKU ID",
  "wareId": "integer (optional) - 仓库 ID，不提供则查询所有仓库"
}
```

**调用接口：** `GET {guli.ware.base-url}/wareSku/list?skuId={skuId}&wareId={wareId}`

**返回数据：** 格式化后的库存信息，包括：
- 各仓库的总库存、锁定库存、可用库存
- 库存汇总
- 库存状态提示（缺货/紧张/较少/充足）

### 4. MCP Server 配置 (McpServerConfig.java)

自动注册所有 `McpServerFeatures.SyncToolSpecification` Bean 到 MCP Server。

## 配置文件

### application.yml

```yaml
server:
  port: 9099

spring:
  application:
    name: ragent-mcp-server

# 谷粒商城服务配置
guli:
  product:
    base-url: http://localhost:8080/product
  ware:
    base-url: http://localhost:8080/ware
```

## 部署步骤

### 前置条件

1. **谷粒商城服务启动**
   - `guli-product` 服务运行在 `http://localhost:8080/product`
   - `guli-ware` 服务运行在 `http://localhost:8080/ware`

2. **验证接口可访问**
   ```bash
   # 测试商品详情接口
   curl http://localhost:8080/product/display/item/1
   
   # 测试库存查询接口
   curl http://localhost:8080/ware/wareSku/list?skuId=1
   ```

### 启动 MCP Server

```bash
cd /workspace/mcp-server
mvn clean package -DskipTests
java -jar target/ragent-mcp-server-0.0.1-SNAPSHOT.jar
```

或指定外部配置：

```bash
java -jar target/ragent-mcp-server-0.0.1-SNAPSHOT.jar \
  --guli.product.base-url=http://guli-product:8080/product \
  --guli.ware.base-url=http://guli-ware:8080/ware
```

### 验证 MCP Server

MCP Server 启动后，会在端口 9099 提供 HTTP 端点：
- MCP 协议端点：`http://localhost:9099/mcp`

## 使用示例

### 场景 1：查询商品详情

**用户问题：** "帮我查一下 SKU 为 1 的商品详情"

**Agent 处理流程：**
1. 意图识别：命中"商品详情查询"意图
2. 参数提取：从问题中提取 `skuId=1`
3. 工具调用：调用 `product_detail_query` 工具
4. 接口请求：`GET http://localhost:8080/product/display/item/1`
5. 结果返回：格式化商品详情信息

### 场景 2：查询商品库存

**用户问题：** "SKU 1001 现在还有货吗？"

**Agent 处理流程：**
1. 意图识别：命中"库存查询"意图
2. 参数提取：从问题中提取 `skuId=1001`
3. 工具调用：调用 `product_stock_query` 工具
4. 接口请求：`GET http://localhost:8080/ware/wareSku/list?skuId=1001`
5. 结果返回：格式化库存信息 + 库存状态提示

### 场景 3：查询指定仓库库存

**用户问题：** "查一下 SKU 1 在仓库 2 的库存"

**Agent 处理流程：**
1. 意图识别：命中"库存查询"意图
2. 参数提取：`skuId=1`, `wareId=2`
3. 工具调用：调用 `product_stock_query` 工具
4. 接口请求：`GET http://localhost:8080/ware/wareSku/list?skuId=1&wareId=2`
5. 结果返回：指定仓库的库存详情

## 意图检索树配置

在 Ragent 的 `IntentTreeFactory.java` 中配置意图节点：

```java
// 电商领域 - 商品信息分类
IntentNode productInfoNode = new IntentNode("商品信息");

// 商品详情查询意图
IntentNode productDetailIntent = new IntentNode("商品详情查询");
productDetailIntent.setMcpToolId("product_detail_query");
productDetailIntent.setParameterExtractPrompt(MCP_PRODUCT_DETAIL_PARAMETER_EXTRACT_PROMPT);
productDetailIntent.setResultTemplate(MCP_PRODUCT_DETAIL_PROMPT_TEMPLATE);

// 库存查询意图
IntentNode stockQueryIntent = new IntentNode("库存查询");
stockQueryIntent.setMcpToolId("product_stock_query");
stockQueryIntent.setParameterExtractPrompt(MCP_PRODUCT_STOCK_PARAMETER_EXTRACT_PROMPT);
stockQueryIntent.setResultTemplate(MCP_PRODUCT_STOCK_PROMPT_TEMPLATE);

productInfoNode.addChild(productDetailIntent);
productInfoNode.addChild(stockQueryIntent);
```

## 错误处理

### 常见错误及解决方案

| 错误现象 | 可能原因 | 解决方案 |
|---------|---------|---------|
| 连接超时 | 谷粒商城服务未启动 | 检查服务状态，确保端口可访问 |
| 404 Not Found | 接口路径错误或 SKU 不存在 | 验证接口路径和参数 |
| 500 Internal Error | 谷粒商城内部错误 | 查看 guli 服务日志 |
| 参数解析失败 | 参数格式不正确 | 确保 skuId 为正整数 |

## 扩展指南

### 添加新的 Tool

1. 创建新的 Executor 类，继承 MCP Tool 规范
2. 定义 Tool ID、描述、输入 Schema
3. 实现 `handleCall` 方法进行真实 HTTP 调用
4. 使用 `@Bean` 注册 `SyncToolSpecification`

### 支持更多业务场景

可扩展的 Tool 列表：
- `order_query` - 订单状态查询
- `logistics_query` - 物流信息查询
- `price_query` - 价格查询
- `promotion_query` - 促销活动查询

## 关键设计原则

1. **真实调用**：所有 Tool 都通过 HTTP 真实调用谷粒商城接口，不使用模拟数据
2. **配置驱动**：通过 `application.yml` 配置服务地址，支持环境切换
3. **错误友好**：提供清晰的错误提示和状态反馈
4. **易于扩展**：遵循统一的 Tool 实现模式，便于添加新业务 Tool

## 项目结构

```
mcp-server/
├── src/main/java/com/nageoffer/ai/ragent/mcp/
│   ├── McpServerApplication.java          # 启动类
│   ├── config/
│   │   └── McpServerConfig.java           # MCP Server 配置
│   └── executor/
│       ├── GuliMcpProperties.java         # 配置属性类
│       ├── GuliProductDetailMcpExecutor.java  # 商品详情工具
│       └── GuliProductStockMcpExecutor.java   # 库存查询工具
└── src/main/resources/
    └── application.yml                    # 配置文件
```

## 参考链接

- 谷粒商城 GitHub: https://github.com/MrliCST/guli2
- MCP 协议规范：https://modelcontextprotocol.io/

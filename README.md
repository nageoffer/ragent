# RAgent

<p align="center">
  <strong>企业级 RAG 智能体服务</strong><br/>
  基于 Spring Boot 构建的智能文档处理与检索增强生成系统
</p>

<p align="center">
  <img src="https://img.shields.io/badge/JDK-17+-green.svg" alt="JDK Version"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5.x-brightgreen.svg" alt="Spring Boot"/>
  <img src="https://img.shields.io/badge/Milvus-2.6.x-blue.svg" alt="Milvus"/>
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"/>
</p>

---

## 简介

RAgent 是一个功能完备的企业级 RAG（Retrieval-Augmented Generation）智能体服务，集成向量数据库，提供智能问答、知识库管理、会话记忆、意图识别、深度思考等能力。支持多模型路由、MCP 工具调用，开箱即用。

## 核心特性

### RAG 能力
- **文档解析与索引**：支持 PDF / Markdown / DOC / DOCX 等格式，自动切分为语义 Chunk 并入库
- **向量检索**：基于 Milvus HNSW + COSINE 高效向量召回
- **结果重排序**：支持 Rerank 模型精排，提升 TopK 命中质量
- **Query 重写**：上下文感知的问题改写，提升检索准确率
- **流式输出**：SSE / 纯文本流式响应，实时推送生成内容

### 智能体能力
- **意图识别**：基于意图树的多层级意图分类
- **MCP 工具调用**：可扩展的工具执行框架，支持自定义工具注册
- **会话记忆**：多轮对话记忆管理，支持摘要压缩
- **深度思考**：支持深度思考模式（Deep Thinking），适配推理增强模型

### 企业级特性
- **多模型路由**：统一模型调度，支持故障转移与优先级策略
- **知识库管理**：完整的知识库 CRUD、文档管理、Chunk 管理
- **用户认证**：集成 Sa-Token 权限框架
- **分布式支持**：Redis 缓存 + MySQL 持久化

## 项目架构

```
ragent/
├── bootstrap/          # 启动模块：控制器、服务、RAG 核心逻辑
│   ├── controller/     # REST API 控制器
│   ├── service/        # 业务服务层
│   ├── rag/            # RAG 核心组件
│   │   ├── chunk/      #   文档切分策略
│   │   ├── extractor/  #   文本提取器
│   │   ├── intent/     #   意图识别
│   │   ├── mcp/        #   MCP 工具框架
│   │   ├── memory/     #   会话记忆
│   │   ├── prompt/     #   Prompt 模板
│   │   ├── retrieve/   #   检索服务
│   │   ├── rewrite/    #   Query 重写
│   │   └── vector/     #   向量存储
│   └── dao/            # 数据访问层
├── framework/          # 基础框架：通用工具、异常处理、Web 配置
├── infra-ai/           # AI 基础设施：LLM/Embedding/Rerank 抽象与实现
│   ├── chat/           #   LLM 对话服务
│   ├── embedding/      #   向量化服务
│   ├── rerank/         #   重排序服务
│   └── model/          #   模型路由与调度
└── mcp-server/         # MCP Server 模块（扩展）
```

## 技术栈

| 类别 | 技术 |
|------|------|
| 框架 | Spring Boot 3.5.x |
| 向量数据库 | Milvus 2.6.x |
| 关系数据库 | MySQL 8.x |
| 缓存 | Redis |
| 文档解析 | Apache Tika 3.x |
| 认证授权 | Sa-Token |
| HTTP 客户端 | OkHttp |
| 工具库 | Hutool、Guava、Gson |
| 对象存储 | S3 兼容（RustFS/MinIO） |

## 支持的模型厂商

| 厂商 | Chat | Embedding | Rerank |
|------|------|-----------|--------|
| 阿里百炼（DashScope） | ✅ | - | ✅ |
| SiliconFlow | ✅ | ✅ | - |
| Ollama（本地） | ✅ | ✅ | - |

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.9+
- MySQL 8.x
- Redis
- Milvus 2.6.x

### 1. 启动基础设施

使用 Docker Compose 启动 Milvus 及依赖服务：

```bash
cd resources/docker/milvus
docker compose -f milvus-stack-2.6.6.compose.yaml up -d
```

该命令将启动：
- Milvus Standalone（端口 19530）
- RustFS 对象存储（端口 9000）
- Etcd
- Attu 可视化管理界面（端口 8000）

### 2. 配置数据库

创建 MySQL 数据库 `ragent`，并执行相关建表脚本。

### 3. 配置应用

修改 `bootstrap/src/main/resources/application.yaml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/ragent?...
    username: your_username
    password: your_password
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      password: your_redis_password

# AI 模型配置
ai:
  providers:
    bailian:
      api-key: ${BAILIAN_API_KEY:sk-xxx}
    siliconflow:
      api-key: ${SILICONFLOW_API_KEY:sk-xxx}
```

### 4. 启动应用

```bash
# 开发模式
./mvnw spring-boot:run -pl bootstrap

# 或打包运行
./mvnw clean package -DskipTests
java -jar bootstrap/target/bootstrap-*.jar
```

应用启动后访问：`http://localhost:8080/api/ragent`

## API 接口

### RAG 问答

| 版本 | 路径 | 特性 |
|------|------|------|
| V1 快速版 | `/api/ragent/rag/v1/*` | 简单检索 + LLM |
| V2 标准版 | `/api/ragent/rag/v2/*` | + 意图识别 + Query 重写 + Rerank |
| V3 企业版 | `/api/ragent/rag/v3/*` | + MCP 工具 + 记忆系统 + 深度思考 |

#### V3 企业版（推荐）

```bash
# SSE 流式问答
curl -H "Accept: text/event-stream" \
  "http://localhost:8080/api/ragent/rag/v3/chat?question=公司如何开票?&conversationId=xxx&deepThinking=false"

# 停止任务
curl -X POST "http://localhost:8080/api/ragent/rag/v3/stop?taskId=xxx"
```

#### V1/V2 快速问答

```bash
# SSE 流式
curl "http://localhost:8080/api/ragent/rag/v1/chat?question=公司如何开票?&topK=3"

# 纯文本流式
curl "http://localhost:8080/api/ragent/rag/v1/stream-text?question=公司如何开票?&topK=3"
```

### 知识库管理

```bash
# 创建知识库
curl -X POST http://localhost:8080/api/ragent/knowledge-base \
  -H "Content-Type: application/json" \
  -d '{"name": "公司规章制度", "description": "内部规章文档库"}'

# 查询知识库列表
curl "http://localhost:8080/api/ragent/knowledge-base?pageNo=1&pageSize=10"

# 查询知识库详情
curl http://localhost:8080/api/ragent/knowledge-base/{kb-id}

# 重命名知识库
curl -X PUT http://localhost:8080/api/ragent/knowledge-base/{kb-id} \
  -H "Content-Type: application/json" \
  -d '{"name": "新名称"}'

# 删除知识库
curl -X DELETE http://localhost:8080/api/ragent/knowledge-base/{kb-id}
```

### 文档管理

```bash
# 上传文档
curl -X POST http://localhost:8080/api/ragent/knowledge-base/{kb-id}/docs/upload \
  -F "file=@/path/to/document.pdf"

# 开始分块（解析 -> 切分 -> 向量化 -> 入库）
curl -X POST http://localhost:8080/api/ragent/knowledge-base/docs/{doc-id}/chunk

# 查询文档列表
curl "http://localhost:8080/api/ragent/knowledge-base/{kb-id}/docs?pageNo=1&pageSize=10"

# 启用/禁用文档
curl -X PATCH "http://localhost:8080/api/ragent/knowledge-base/docs/{doc-id}/enable?value=true"

# 删除文档
curl -X DELETE http://localhost:8080/api/ragent/knowledge-base/docs/{doc-id}
```

### Chunk 管理

```bash
# 查询文档的 Chunk 列表
curl "http://localhost:8080/api/ragent/knowledge-base/docs/{doc-id}/chunks?pageNo=1&pageSize=20"

# 新增 Chunk
curl -X POST http://localhost:8080/api/ragent/knowledge-base/docs/{doc-id}/chunks \
  -H "Content-Type: application/json" \
  -d '{"content": "自定义内容"}'

# 更新 Chunk
curl -X PUT http://localhost:8080/api/ragent/knowledge-base/docs/{doc-id}/chunks/{chunk-id} \
  -H "Content-Type: application/json" \
  -d '{"content": "更新后的内容"}'

# 启用/禁用 Chunk
curl -X POST http://localhost:8080/api/ragent/knowledge-base/docs/{doc-id}/chunks/{chunk-id}/enable
curl -X POST http://localhost:8080/api/ragent/knowledge-base/docs/{doc-id}/chunks/{chunk-id}/disable

# 重建文档向量
curl -X POST http://localhost:8080/api/ragent/knowledge-base/docs/{doc-id}/chunks/rebuild
```

### 意图树管理

```bash
# 获取意图树
curl http://localhost:8080/api/ragent/intent-tree/trees

# 创建意图节点
curl -X POST http://localhost:8080/api/ragent/intent-tree \
  -H "Content-Type: application/json" \
  -d '{"name": "报销咨询", "parentId": null, "keywords": ["报销", "费用"]}'

# 更新意图节点
curl -X PUT http://localhost:8080/api/ragent/intent-tree/{id} \
  -H "Content-Type: application/json" \
  -d '{"name": "新名称"}'

# 删除意图节点
curl -X DELETE http://localhost:8080/api/ragent/intent-tree/{id}
```

### 会话管理

```bash
# 获取会话列表
curl http://localhost:8080/api/ragent/conversations

# 获取会话消息
curl http://localhost:8080/api/ragent/conversations/{conversationId}/messages

# 重命名会话
curl -X PUT http://localhost:8080/api/ragent/conversations/{conversationId} \
  -H "Content-Type: application/json" \
  -d '{"title": "新标题"}'

# 删除会话
curl -X DELETE http://localhost:8080/api/ragent/conversations/{conversationId}
```

### 认证

```bash
# 登录
curl -X POST http://localhost:8080/api/ragent/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "xxx"}'

# 登出
curl -X POST http://localhost:8080/api/ragent/auth/logout \
  -H "Authorization: {token}"
```

## 配置说明

### AI 模型配置

```yaml
ai:
  # 模型厂商配置
  providers:
    ollama:
      url: http://localhost:11434
      endpoints:
        chat: /api/chat
        embedding: /api/embed
    bailian:
      url: https://dashscope.aliyuncs.com
      api-key: ${BAILIAN_API_KEY:}
    siliconflow:
      url: https://api.siliconflow.cn
      api-key: ${SILICONFLOW_API_KEY:}

  # 对话模型配置
  chat:
    default-model: qwen-plus
    deep-thinking-model: glm-4.7    # 深度思考专用模型
    candidates:
      - id: qwen-plus
        provider: bailian
        model: qwen-plus-latest
        priority: 1

  # 向量化模型配置
  embedding:
    default-model: qwen-emb-8b
    candidates:
      - id: qwen-emb-8b
        provider: siliconflow
        model: Qwen/Qwen3-Embedding-8B
        dimension: 4096
        priority: 1

  # 重排序模型配置
  rerank:
    default-model: qwen3-rerank
    candidates:
      - id: qwen3-rerank
        provider: bailian
        model: qwen3-rerank
        priority: 1
```

### RAG 配置

```yaml
rag:
  # 默认向量空间配置
  default:
    collection-name: rag_default_store
    dimension: 4096
    metric-type: COSINE

  # Query 重写配置
  query-rewrite:
    enabled: true
    max-history-messages: 4
    max-history-chars: 500

  # 会话记忆配置
  memory:
    history-keep-turns: 4       # 保留最近对话轮数
    summary-start-turns: 5      # 触发摘要的轮数
    summary-enabled: true
    ttl-minutes: 60
```

## 工作原理

```
┌─────────────────────────────────────────────────────────────────┐
│                         用户问题                                 │
└─────────────────────────────────┬───────────────────────────────┘
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                       意图识别 (Intent)                          │
│              基于意图树分类，确定问题类型                          │
└─────────────────────────────────┬───────────────────────────────┘
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Query 重写 (Rewrite)                          │
│           结合上下文改写问题，消除指代、补全信息                    │
└─────────────────────────────────┬───────────────────────────────┘
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                      向量检索 (Retrieve)                         │
│      问题向量化 → Milvus TopK 召回 → 候选 Chunk 列表              │
└─────────────────────────────────┬───────────────────────────────┘
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                      重排序 (Rerank)                             │
│              Rerank 模型精排，筛选最相关 Chunk                    │
└─────────────────────────────────┬───────────────────────────────┘
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                   MCP 工具调用 (可选)                             │
│           根据意图调用外部工具获取实时数据                         │
└─────────────────────────────────┬───────────────────────────────┘
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Prompt 组装 & LLM 生成                        │
│      系统提示 + 历史对话 + 上下文 + 问题 → 流式生成答案            │
└─────────────────────────────────────────────────────────────────┘
```

## 前端集成

### SSE 流式接入

```javascript
const es = new EventSource('/api/ragent/rag/v3/chat?question=问题&conversationId=xxx');

es.onmessage = (event) => {
  console.log('收到内容:', event.data);
};

es.onerror = (error) => {
  console.error('连接错误:', error);
  es.close();
};
```

### Fetch 流式接入

```javascript
async function streamChat(question) {
  const response = await fetch(`/api/ragent/rag/v1/stream-text?question=${encodeURIComponent(question)}`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    console.log(decoder.decode(value));
  }
}
```

## Roadmap

- [ ] **多模态支持**：图片、表格、代码块的解析与检索
- [ ] **更多模型厂商**：OpenAI / DeepSeek / Azure / 本地 vLLM
- [ ] **索引策略优化**：更智能的切分、摘要与段落关联
- [ ] **元数据过滤**：按文档类型、标签、业务线进行检索过滤
- [ ] **引用溯源**：返回答案所依据的文档片段，支持引用展示
- [ ] **评测与监控**：召回率、精准率、延迟与成本的监控面板
- [ ] **权限与隔离**：多租户数据隔离与细粒度权限控制

## 许可证

本项目采用 [Apache License 2.0](LICENSE) 许可证。

---

<p align="center">
  Made with ❤️ by <a href="https://github.com/nageoffer">Nageoffer</a>
</p>

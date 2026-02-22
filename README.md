# RAgent

![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)
![Java](https://img.shields.io/badge/Java-17-ff7f2a.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.x-6db33f.svg)
![React](https://img.shields.io/badge/React-18-61dafb.svg)
![Milvus](https://img.shields.io/badge/Milvus-2.6.x-00b3ff.svg)

> 企业级 RAG 智能体平台（Monorepo）
>
> 把知识库检索、多通道召回、MCP 工具调用、会话记忆、流式输出、链路追踪、数据入库流水线放在同一套工程里。

后端默认入口：`http://localhost:8080/api/ragent`

## 目录

- [项目定位](#项目定位)
- [核心能力](#核心能力)
- [核心流程重点](#核心流程重点)
- [架构总览](#架构总览)
- [代码导航](#代码导航)
- [快速启动](#快速启动)
- [配置速查](#配置速查)
- [API 地图核心](#api-地图核心)
- [SSE 事件协议v3](#sse-事件协议v3)
- [扩展点像框架一样扩](#扩展点像框架一样扩)
- [开发建议与注意事项](#开发建议与注意事项)
- [Roadmap Ideas](#roadmap-ideas)
- [Contributing](#contributing)
- [License](#license)

## 项目定位

RAgent 不是“只有一个 `/chat` 接口”的 Demo，而是一套偏工程化的 RAG Agent 平台，目标是解决真实业务里常见的几个难点：

- 检索覆盖率不足：通过意图定向检索 + 全局向量兜底 + 后处理器链提升命中率与质量。
- 工具调用与知识检索混用：MCP 工具结果和 KB 文档结果统一进入 Prompt 编排。
- 长会话上下文失控：支持会话记忆、摘要、标题生成、消息持久化。
- 模型可用性不稳定：支持多模型候选、失败降级、健康状态与熔断恢复。
- 线上可观测性差：提供 RAG Trace（run/node 维度）与管理后台视图。
- 文档入库流程分散：内置可编排的 Ingestion Pipeline（抓取/解析/增强/分块/索引）。

## 核心能力

- `RAG v3` 流式对话主链路（SSE）
- Query Rewrite + 多问句拆分
- 意图识别 + 歧义引导（可提前返回引导文案）
- 多通道 KB 检索（`SearchChannel`）
- 后处理器链（`SearchResultPostProcessor`）
- MCP 工具参数抽取 / 执行 / 聚合上下文
- Prompt 场景编排（KB only / MCP only / Mixed）
- LLM 路由（优先级、失败切换、流式首包探测）
- 会话记忆、摘要、反馈、任务取消
- RAG Trace 可观测性（run / node）
- Ingestion Pipeline（`fetcher -> parser -> enhancer -> chunker -> enricher -> indexer`）
- 管理后台（React + Vite + TypeScript）

## 核心流程（重点）

这一节是整个项目的“心智模型”。先读完这几条流程，再进源码会快很多。

### 1. 在线问答主链路（RAG v3 / 推荐）

请求入口：`GET /rag/v3/chat`（SSE）

```text
User Question
  -> 会话初始化 / taskId / conversationId
  -> 记忆加载（history + append 当前问题）
  -> Query Rewrite + 多问句拆分
  -> 子问题意图识别
  -> 歧义引导判定（命中则直接返回引导，不再继续）
  -> RetrievalEngine
       -> KB: MultiChannelRetrievalEngine（多通道检索 + 后处理器链）
       -> MCP: 参数抽取 -> 工具执行 -> 结果聚合
  -> RAGPromptService（组装 system / context / history / user）
  -> RoutingLLMService（模型选择 / 流式首包探测 / 失败切换）
  -> SSE 推送（meta/message/finish/...）
  -> 消息落库 / 标题生成 / trace 记录
```

对应关键代码：

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RAGChatController.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrievalEngine.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptService.java`
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/RoutingLLMService.java`

### 2. 检索内核（KB 多通道检索）

`RetrievalEngine` 负责“总编排”，`MultiChannelRetrievalEngine` 专注 KB 检索本身。

```text
SubQuestions
  -> SearchContext
  -> 并行执行启用的 SearchChannel（按 priority 排序）
       - IntentDirectedSearchChannel（意图定向）
       - VectorGlobalSearchChannel（全局向量兜底，按阈值触发）
  -> 汇总 SearchChannelResult
  -> 后处理器链（按 order 排序）
       - DeduplicationPostProcessor
       - RerankPostProcessor
  -> Final Retrieved Chunks (Top-K)
```

这套设计的价值：

- 检索策略可插拔（新增一个通道就是实现 `SearchChannel`）
- 后处理逻辑可组合（去重、rerank、过滤、合并都能独立演进）
- 容错更稳（单通道失败不拖垮整条检索链）

### 3. MCP 工具调用链路（与 KB 并行共存）

当意图命中 `MCP` 类型节点时，系统会进入工具执行路径：

```text
Question
  -> 命中 MCP IntentNode（携带 mcpToolId）
  -> MCPToolRegistry 找到对应 MCPToolExecutor
  -> MCPParameterExtractor 抽取参数（可使用节点自定义参数 Prompt）
  -> MCPService 批量执行工具
  -> ContextFormatter 格式化为 MCP 上下文片段
  -> 与 KB 上下文一起进入 Prompt 编排
```

内置示例工具：

- `sales_query`（`SalesMCPExecutor`）

### 4. Ingestion Pipeline（文档入库链路）

入口支持文件上传、URL、飞书、S3 等来源。核心思想是“流水线节点化”。

```text
Source (file/url/feishu/s3)
  -> FetcherNode   （抓取原始内容）
  -> ParserNode    （文本解析，含 Tika 等）
  -> EnhancerNode  （内容增强）
  -> ChunkerNode   （分块）
  -> EnricherNode  （片段增强/标签化）
  -> IndexerNode   （向量化 + 写入 Milvus）
  -> 任务日志 / 节点日志落库
```

对应关键代码：

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/engine/IngestionEngine.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/FetcherNode.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/ParserNode.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/ChunkerNode.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/IndexerNode.java`

### 5. 模型路由与容错（Chat / Embedding / Rerank）

`infra-ai` 层把模型调用做成了“路由器”而不是“写死某一个 Provider”：

- 多候选模型按优先级选择
- 失败自动切换下一个候选
- 模型健康状态记录（失败计数）
- 熔断窗口（避免持续打坏模型）
- 流式首包探测，避免失败模型的半截输出污染前端

这使得线上运行时能在 `Ollama / 百炼 / SiliconFlow` 等提供商之间更稳定地切换。

## 架构总览

### 0. 一图看懂（Runtime + Modules）

![RAgent Runtime Architecture](docs/assets/ragent-architecture-overview.svg)

说明：

- 图是可编辑 `SVG`，位于 `docs/assets/ragent-architecture-overview.svg`
- 展示的是“运行时主链路 + 模块职责”，不是类图/时序图

### 1. Monorepo 分层

```text
ragent/
├── bootstrap      # Spring Boot 应用入口 + 业务实现（RAG / Ingestion / Admin / User）
├── framework      # 通用基础层（Result/Exception/Context/Trace/Idempotent）
├── infra-ai       # AI 基础设施（Chat/Embedding/Rerank 客户端与模型路由）
├── mcp-server     # MCP 扩展模块（当前偏预留/扩展位）
├── frontend       # React + Vite 管理台与聊天前端
├── docs           # 架构说明与示例文档
└── resources      # 基础设施资源（如 Milvus compose）
```

### 2. 运行时拓扑（Runtime Topology）

```text
Browser (frontend:5173)
  -> /api/ragent/* (Vite Proxy 或直接 API Base URL)
  -> Spring Boot (bootstrap:8080)
       -> framework (上下文/返回值/trace/幂等)
       -> rag core (rewrite/intent/retrieve/prompt/memory/mcp)
       -> ingestion engine (pipeline + nodes)
       -> infra-ai (chat/embedding/rerank routing)
       -> MySQL / Redis / Milvus / RustFS(S3)
```

## 代码导航

如果你准备二开，建议按下面顺序看代码：

1. 请求入口与编排
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RAGChatController.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`

2. 检索与工具链
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrievalEngine.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/`

3. Prompt 与模型调用
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptService.java`
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/RoutingLLMService.java`

4. 数据入库链路
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/engine/IngestionEngine.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/`

5. 可观测性与治理
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/aop/RagTraceAspect.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RagTraceController.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/aop/ChatRateLimitAspect.java`

## 快速启动

### 1. 环境要求

- JDK `17+`
- Maven `3.9+`
- Node.js `18+`
- MySQL `8+`
- Redis
- Docker（用于 Milvus / RustFS）

### 2. 启动向量与对象存储依赖（Milvus + RustFS）

```bash
cd resources/docker/milvus
docker compose -f milvus-stack-2.6.6.compose.yaml up -d
```

会启动（按 compose 配置）：

- `milvus-standalone`（默认 `19530`）
- `rustfs`（默认 `9000`）
- `etcd`
- `attu`（默认 `8000`，Milvus 可视化）

### 3. 配置后端

编辑 `bootstrap/src/main/resources/application.yaml`。

至少检查这些项：

- `spring.datasource.*`（MySQL）
- `spring.data.redis.*`（Redis）
- `milvus.uri`
- `rustfs.*`
- `ai.providers.*.api-key`（如使用百炼 / SiliconFlow）

默认示例值（仅适合本地开发）：

- `server.port=8080`
- `server.servlet.context-path=/api/ragent`
- MySQL：`root/root`
- Redis：`127.0.0.1:6379`（示例密码 `123456`）

可选环境变量（推荐）：

```bash
export BAILIAN_API_KEY=xxx
export SILICONFLOW_API_KEY=xxx
```

### 4. 启动后端（Spring Boot）

```bash
./mvnw -pl bootstrap spring-boot:run
```

或打包运行：

```bash
./mvnw clean package -DskipTests
java -jar bootstrap/target/bootstrap-*.jar
```

### 5. 启动前端（React + Vite）

```bash
cd frontend
npm install
```

创建 `frontend/.env.local`（推荐）：

```bash
VITE_API_BASE_URL=/api/ragent
```

说明：

- 使用上面配置时，前端通过 Vite 代理访问后端（`/api` -> `http://localhost:8080`）
- 如果不走代理，可改为：`VITE_API_BASE_URL=http://localhost:8080/api/ragent`

启动前端：

```bash
npm run dev
```

访问：

- 前端：`http://localhost:5173`
- 后端：`http://localhost:8080/api/ragent`

## 配置速查

### 1. 模型配置（`ai.*`）

项目内置了三类 AI 能力路由：

- `ai.chat.*`
- `ai.embedding.*`
- `ai.rerank.*`

每类都支持：

- `default-model`
- `candidates[]`（候选模型列表）
- `priority`（优先级）

全局容错策略：

- `ai.selection.failure-threshold`：失败阈值
- `ai.selection.open-duration-ms`：熔断打开时长

### 2. RAG 配置（`rag.*`）

- `rag.query-rewrite.*`：改写与历史截断
- `rag.rate-limit.*`：全局并发限流
- `rag.memory.*`：会话记忆与摘要策略
- `rag.search.channels.*`：检索通道触发阈值/候选倍数
- `rag.trace.*`：链路追踪开关与错误长度

### 3. 检索策略关键参数（推荐先理解）

- `rag.search.channels.vector-global.confidence-threshold`
- `rag.search.channels.vector-global.top-k-multiplier`
- `rag.search.channels.intent-directed.min-intent-score`
- `rag.search.channels.intent-directed.top-k-multiplier`

## API 地图（核心）

Base Path：`/api/ragent`

### 1. 认证与用户

- `POST /auth/login`
- `POST /auth/logout`
- `GET /user/me`
- `GET /users`
- `POST /users`

### 2. RAG 对话与会话

- `GET /rag/v3/chat`（SSE）
- `POST /rag/v3/stop`
- `GET /conversations`
- `GET /conversations/{conversationId}/messages`
- `POST /conversations/messages/{messageId}/feedback`

### 3. 知识库与文档

- `POST /knowledge-base`
- `GET /knowledge-base`
- `GET /knowledge-base/{kb-id}`
- `POST /knowledge-base/{kb-id}/docs/upload`
- `POST /knowledge-base/docs/{doc-id}/chunk`
- `GET /knowledge-base/{kb-id}/docs`
- `GET /knowledge-base/docs/search`
- `GET /knowledge-base/docs/{docId}/chunk-logs`
- `GET /knowledge-base/docs/{doc-id}/chunks`
- `POST /knowledge-base/docs/{doc-id}/chunks`

### 4. Ingestion Pipeline

- `POST /ingestion/pipelines`
- `GET /ingestion/pipelines/{id}`
- `GET /ingestion/pipelines`
- `POST /ingestion/tasks`
- `POST /ingestion/tasks/upload`
- `GET /ingestion/tasks/{id}`
- `GET /ingestion/tasks/{id}/nodes`
- `GET /ingestion/tasks`

### 5. 配置与观测

- `GET /rag/settings`
- `GET /rag/traces/runs`
- `GET /rag/traces/runs/{traceId}`
- `GET /rag/traces/runs/{traceId}/nodes`
- `GET /admin/dashboard/overview`
- `GET /admin/dashboard/performance`
- `GET /admin/dashboard/trends`

说明：完整接口请直接查看 `bootstrap/src/main/java/com/nageoffer/ai/ragent/**/controller/`。

## SSE 事件协议（v3）

`GET /rag/v3/chat` 以事件流返回：

- `meta`：`{ conversationId, taskId }`
- `message`：`{ type: "response" | "think", delta }`
- `finish`：`{ messageId, title }`
- `cancel`：`{ messageId, title }`
- `reject`：`{ type: "response", delta }`
- `done`：`[DONE]`

### curl 调试示例（SSE）

说明：

- 使用 `curl -N` 关闭缓冲，才能看到流式输出
- 如果开启了鉴权，请带上 `Authorization`（`sa-token` 默认 token 名）
- `question` 必填，`conversationId` / `deepThinking` 可选

```bash
curl -N -G 'http://localhost:8080/api/ragent/rag/v3/chat' \
  -H 'Accept: text/event-stream' \
  -H 'Authorization: <YOUR_TOKEN>' \
  --data-urlencode 'question=请总结一下当前知识库里关于发票报销的规则' \
  --data-urlencode 'deepThinking=false'
```

带会话 ID（续聊）示例：

```bash
curl -N -G 'http://localhost:8080/api/ragent/rag/v3/chat' \
  -H 'Accept: text/event-stream' \
  -H 'Authorization: <YOUR_TOKEN>' \
  --data-urlencode 'question=再给我一个报销材料清单' \
  --data-urlencode 'conversationId=<CONVERSATION_ID>'
```

停止任务示例（`taskId` 从 `meta` 事件中获取）：

```bash
curl -X POST 'http://localhost:8080/api/ragent/rag/v3/stop' \
  -H 'Authorization: <YOUR_TOKEN>' \
  --data-urlencode 'taskId=<TASK_ID>'
```

## 扩展点（像框架一样扩）

如果你想把 RAgent 当底座来做二开，下面这些接口是第一批扩展位。

### 1. 扩检索通道

实现：`SearchChannel`

- 文件：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchChannel.java`
- 场景：关键词检索、ES 检索、BM25、结构化字段检索、图谱检索

### 2. 扩后处理器链

实现：`SearchResultPostProcessor`

- 文件：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/SearchResultPostProcessor.java`
- 场景：版本过滤、租户过滤、时间衰减、规则重排、去噪

### 3. 扩 MCP 工具

实现：`MCPToolExecutor`

- 文件：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/MCPToolExecutor.java`
- 注册：作为 Spring Bean 自动发现（见 `DefaultMCPToolRegistry`）

### 4. 扩 Ingestion 节点能力

关注目录：`bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/`

- 可新增节点类型或增强已有节点策略
- 适合接入 OCR、结构化抽取、实体标注、合规脱敏等流程

### 5. 扩模型提供商

关注 `infra-ai` 层：

- Chat 客户端：`infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/`
- Embedding 客户端：`infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/embedding/`
- Rerank 客户端：`infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/rerank/`

## 开发建议与注意事项

- 当前仓库未内置建表 SQL，请按实体建表或导入已有库结构。
- `mcp-server` 模块当前更偏扩展位，不是主运行链路入口。
- 默认配置更偏本地开发，请在生产环境替换数据库密码、对象存储密钥、模型 API Key。
- 向量维度需与 Embedding 模型配置保持一致（当前默认示例是 `4096`）。

常见核心表（示例）：

- `t_user`
- `t_conversation`
- `t_message`
- `t_conversation_summary`
- `t_intent_node`
- `t_knowledge_base`
- `t_knowledge_document`
- `t_knowledge_chunk`
- `t_ingestion_pipeline`
- `t_ingestion_pipeline_node`

## Roadmap Ideas

以下是从当前架构自然延伸出来的方向（适合开源协作）：

- 更多检索通道（ES/BM25/Hybrid Search）
- 多租户隔离与权限级检索过滤
- SQL schema / migration（Flyway/Liquibase）内置化
- 更完整的 MCP 工具生态与权限治理
- 离线评测集与自动化回归评测（RAG eval）
- Prometheus / OpenTelemetry 接入

## Contributing

欢迎以 Issue / PR 方式参与改进。

建议的本地检查流程：

```bash
# 后端
./mvnw test

# 前端
cd frontend
npm run lint
npm run build
```

提交 PR 时建议附上：

- 改动动机（解决什么问题）
- 核心实现思路（为什么这样做）
- 接口或行为变化说明
- 关键截图 / 日志 / Trace（如涉及前端或链路变化）

## License

Apache License 2.0. See `LICENSE`.

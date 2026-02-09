# RAgent

企业级 RAG 智能体平台，包含：
- 多版本问答链路（`v1/v2/v3`）
- 多通道检索引擎（意图定向 + 全局向量兜底 + 后处理器链）
- 数据通道（Ingestion Pipeline）编排
- MCP 工具调用
- 会话记忆、链路追踪、并发限流
- 管理后台（React + Vite）

后端默认入口：`http://localhost:8080/api/ragent`

## 1. 最新架构总览

### 1.1 模块分层（Monorepo）

```text
ragent
├── bootstrap      # 应用入口与业务实现（Controller/Service/RAG Core/Ingestion/User/Admin）
├── framework      # 通用基础层（统一返回、异常、上下文、幂等、trace基础能力）
├── infra-ai       # AI基础设施层（Chat/Embedding/Rerank 客户端与模型路由）
├── mcp-server     # MCP扩展模块（当前为预留模块）
├── frontend       # React 管理台与聊天前端
├── docs           # 架构与示例文档
└── resources      # 基础设施资源（如 Milvus compose）
```

### 1.2 运行时架构

```text
Browser (frontend:5173)
  -> /api/ragent/* (Vite Proxy or direct API base URL)
  -> Spring Boot (bootstrap:8080)
       -> framework (context/result/trace/idempotent)
       -> rag core (rewrite/intent/retrieve/prompt/memory/mcp)
       -> ingestion engine (pipeline + nodes)
       -> infra-ai (LLM/Embedding/Rerank routing + fallback + circuit breaker)
       -> MySQL / Redis / Milvus / RustFS(S3)
```

## 2. 关键能力

- `RAG v1`：快速检索 + Rerank + LLM 流式回复
- `RAG v2`：在 v1 基础上增加 Query Rewrite + 意图识别
- `RAG v3`：企业链路，支持多问句拆分、MCP 工具、记忆、深度思考、任务取消
- 多通道检索：`IntentDirectedSearchChannel` + `VectorGlobalSearchChannel`
- 后处理器链：`DeduplicationPostProcessor` -> `RerankPostProcessor`
- 数据通道（Ingestion）：支持 `fetcher/parser/enhancer/chunker/enricher/indexer`
- 数据源：`file/url/feishu/s3`
- 文档处理模式：`chunk`（直接分块）或 `pipeline`（走数据通道）
- URL 文档定时刷新：基于 cron 扫描并增量重建
- 模型路由：多候选优先级 + 失败降级 + 熔断恢复
- 认证与权限：Sa-Token，前端区分 `user/admin`
- 链路追踪：RAG run + node 维度追踪（Trace 页面可视化）

## 3. 两条主流程

### 3.1 RAG v3 流程（推荐）

```text
问题输入
  -> 会话上下文加载（memory）
  -> 查询改写与多问句拆分（rewrite）
  -> 子问题并行意图识别（intent）
  -> 歧义引导判定（guidance，可提前返回引导提示）
  -> RetrievalEngine
       -> KB: MultiChannelRetrievalEngine (IntentDirected + conditional VectorGlobal)
       -> MCP: Tool 参数抽取 + 执行 + 聚合
  -> Prompt 组装（KB only / MCP only / Mixed）
  -> 路由LLM流式输出（支持 deepThinking）
  -> SSE 事件推送 + 消息落库 + 标题生成
```

### 3.2 Ingestion Pipeline 流程

```text
创建流水线（节点 + nextNodeId 连线）
  -> 创建任务（source + pipelineId）
  -> IngestionEngine 链式执行节点
       fetcher -> parser -> enhancer -> chunker -> enricher -> indexer
  -> 写入 Milvus，任务与节点日志落库
```

## 4. 技术栈

- Java 17 / Spring Boot 3.5.x
- MyBatis-Plus / MySQL / Redis / Redisson
- Milvus 2.6.x（向量库）
- RustFS（S3 兼容对象存储）
- Apache Tika（文档解析）
- React 18 + Vite + TypeScript + Tailwind + Zustand

## 5. 快速启动

### 5.1 环境要求

- JDK 17+
- Maven 3.9+
- Node.js 18+
- MySQL 8+
- Redis
- Docker（用于 Milvus/RustFS）

### 5.2 启动 Milvus 相关服务

```bash
cd resources/docker/milvus
docker compose -f milvus-stack-2.6.6.compose.yaml up -d
```

会启动：
- `milvus-standalone`（`19530`）
- `rustfs`（`9000`）
- `etcd`
- `attu`（`8000`）

### 5.3 配置后端

编辑 `bootstrap/src/main/resources/application.yaml`：
- `spring.datasource.*`
- `spring.data.redis.*`
- `milvus.uri`
- `rustfs.*`
- `ai.providers.*.api-key`（如使用百炼/SiliconFlow）

说明：
- 默认 `server.port=8080`
- 默认 `server.servlet.context-path=/api/ragent`

### 5.4 启动后端

```bash
./mvnw -pl bootstrap spring-boot:run
```

或：

```bash
./mvnw clean package -DskipTests
java -jar bootstrap/target/bootstrap-*.jar
```

### 5.5 启动前端

```bash
cd frontend
npm install
```

创建 `frontend/.env.local`（推荐）：

```bash
VITE_API_BASE_URL=/api/ragent
```

说明：
- 使用上面配置时，前端通过 Vite 代理(`/api` -> `http://localhost:8080`)访问后端
- 如不走代理，可改成：`VITE_API_BASE_URL=http://localhost:8080/api/ragent`

启动：

```bash
npm run dev
```

访问：
- 前端：`http://localhost:5173`
- 后端：`http://localhost:8080/api/ragent`

## 6. API 地图（核心）

### 6.1 认证与用户

- `POST /auth/login`
- `POST /auth/logout`
- `GET /user/me`
- `GET /users` `POST /users` `PUT /users/{id}` `DELETE /users/{id}`

### 6.2 问答

- `GET /rag/v1/chat`
- `GET /rag/v1/stream-text`
- `GET /rag/v2/chat`
- `GET /rag/v2/stream-text`
- `GET /rag/v3/chat`
- `POST /rag/v3/stop`

### 6.3 会话

- `GET /conversations`
- `PUT /conversations/{conversationId}`
- `DELETE /conversations/{conversationId}`
- `GET /conversations/{conversationId}/messages`
- `POST /conversations/messages/{messageId}/feedback`

### 6.4 知识库与文档

- `POST /knowledge-base` `GET /knowledge-base` `GET /knowledge-base/{kb-id}`
- `PUT /knowledge-base/{kb-id}` `DELETE /knowledge-base/{kb-id}`
- `POST /knowledge-base/{kb-id}/docs/upload`
- `POST /knowledge-base/docs/{doc-id}/chunk`
- `GET /knowledge-base/{kb-id}/docs`
- `PATCH /knowledge-base/docs/{docId}/enable`
- `GET /knowledge-base/docs/search`
- `GET /knowledge-base/docs/{docId}/chunk-logs`
- `GET|POST|PUT|DELETE /knowledge-base/docs/{doc-id}/chunks...`

### 6.5 数据通道（Ingestion）

- `POST /ingestion/pipelines`
- `PUT /ingestion/pipelines/{id}`
- `GET /ingestion/pipelines/{id}`
- `GET /ingestion/pipelines`
- `DELETE /ingestion/pipelines/{id}`
- `POST /ingestion/tasks`
- `POST /ingestion/tasks/upload`
- `GET /ingestion/tasks/{id}`
- `GET /ingestion/tasks/{id}/nodes`
- `GET /ingestion/tasks`

### 6.6 配置与观测

- `GET /rag/settings`
- `GET /rag/traces/runs`
- `GET /rag/traces/runs/{traceId}`
- `GET /rag/traces/runs/{traceId}/nodes`
- `GET /admin/dashboard/overview`
- `GET /admin/dashboard/performance`
- `GET /admin/dashboard/trends`

## 7. SSE 事件协议（v3）

`GET /rag/v3/chat` 会按事件流返回：

- `meta`：`{ conversationId, taskId }`
- `message`：`{ type: "response" | "think", delta }`
- `finish`：`{ messageId, title }`
- `cancel`：`{ messageId, title }`
- `reject`：`{ type: "response", delta }`
- `done`：`[DONE]`

## 8. 内置 MCP 工具

当前默认注册了一个示例工具：
- `sales_query`（销售数据查询，支持汇总/排名/明细/趋势）

可通过实现 `MCPToolExecutor` 并注册 Spring Bean 扩展新工具。

## 9. 数据库说明

当前仓库未内置建表 SQL，请按实体建表或导入已有库结构。主要表包括：

- `t_user`
- `t_conversation` `t_message` `t_conversation_summary`
- `t_intent_node` `t_sample_question`
- `t_query_term_mapping` `t_message_feedback`
- `t_knowledge_base` `t_knowledge_document` `t_knowledge_chunk`
- `t_knowledge_document_chunk_log`
- `t_knowledge_document_schedule` `t_knowledge_document_schedule_exec`
- `t_ingestion_pipeline` `t_ingestion_pipeline_node`
- `t_ingestion_task` `t_ingestion_task_node`
- `t_rag_trace_run` `t_rag_trace_node`

## 10. 相关文档

- 多通道检索：`docs/multi-channel-retrieval.md`
- 重构说明：`docs/refactoring-summary.md`
- 快速说明：`docs/quick-start.md`
- PDF 数据通道示例：`docs/examples/pdf-ingestion-example.md`
- 流水线请求样例：`docs/examples/pdf-pipeline-request.json`

## 11. License

[Apache License 2.0](LICENSE)

# AGENTS.md（fork 基线版）

本文件是 KDB-Wind/ragent fork 的基线版，供 AI 代理（Codex / Claude Code / opencode 等）在本仓库工作时参考。内容基于上游 nageoffer/ragent 的结构精简而来；若与上游 README 或本 fork 实际代码冲突，以本 fork 代码为准。

## 语言偏好

与用户交流使用中文；代码、命令、变量名等技术标识符保持英文。

## 项目简介

Ragent 是一个企业级 Agentic RAG 智能体平台：后端 Java 17 + Spring Boot 3.5.7，前端 React 18 + TypeScript + Vite。覆盖文档入库 ETL、多路检索、意图识别、问题重写、会话记忆、模型路由容错、MCP 工具调用、全链路追踪与管理后台。

## 开发命令

后端（Maven 多模块，根目录）：

```bash
./mvnw -B -ntp spotless:check   # 格式检查（CI 门禁）
./mvnw -B -ntp spotless:apply   # 手动格式化
./mvnw -B -ntp -DskipTests package  # 编译构建（CI 用）
./mvnw -B -ntp test -pl bootstrap -Dtest=QueryRewriteTests  # 单模块/单测试
```

注意：spotless `apply` 绑定在 compile 阶段，改动代码后本地构建会自动格式化；CI 以 `spotless:check` 作为门禁。

前端：

```bash
cd frontend
npm ci
npm run lint
npm run build
npm run dev     # 开发服务器 5173，/api 代理到 localhost:9090
```

## 模块分层

```text
bootstrap   -> infra-ai -> framework
mcp-server  -> (独立应用，无内部模块依赖)
```

- `bootstrap`：业务实现，依赖 infra-ai 与 framework
- `infra-ai`：屏蔽不同模型供应商差异，业务层不直接依赖供应商 SDK
- `framework`：通用能力，不放业务逻辑
- `mcp-server`：独立部署的 MCP Server（端口 9099）

## RAG 核心设计要点

- **多路检索**：`MultiChannelRetrievalEngine` 并行多通道检索 + `SearchResultPostProcessor` 后处理链（去重、重排序）
- **会话记忆**：区分"原始消息窗口 / 摘要记忆 / 最终送模上下文"三层，超限自动摘要压缩
- **模型路由与容错**：Chat / Embedding / Rerank 均为候选模型配置驱动，含优先级、失败阈值、熔断恢复；供应商差异留在 infra-ai
- **入库管线**：文档入库为 `IngestionNode` 节点编排 Pipeline（解析→增强→分块→向量化→写库）

## 扩展点

按 Spring Bean 自动发现，新增能力优先走扩展点而非改核心分发逻辑：

- 新增检索通道：实现 `SearchChannel`
- 新增后处理器：实现 `SearchResultPostProcessor`
- 新增 MCP 工具：实现 `MCPToolExecutor`
- 新增入库节点：实现 `IngestionNode`

## CI 要求

- `backend-maven`：`spotless:check` 与 `-DskipTests package` 必须通过（集成测试依赖外部服务，不在 CI 执行全量 test）
- `frontend-build-lint`：`npm run lint` 与 `npm run build` 必须通过
- main 分支受 ruleset 保护：只能通过 PR 合入，需要 1 个 approve + CODEOWNERS review + 上述 check 全绿

## 协作 owner

模块 owner 见 `.github/CODEOWNERS`，review 时按 owner 路由。

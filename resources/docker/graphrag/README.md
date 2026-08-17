# 知识图谱检索栈（Neo4j + LightRAG）

为多路召回新增「知识图谱」通道所需的基础设施。本栈只新增两个进程：

- **Neo4j 5.26 社区版**：图存储，附带 `apoc` + `graph-data-science`（GDS 社区版含 Leiden/Louvain 社区发现，单机 4 核封顶）
- **LightRAG**（:9621）：图谱构建 + 检索微服务，提供 REST API 与 WebUI

KV / 向量 / 文档状态**复用宿主机已有的 PostgreSQL**（后端同一实例，需已装 pgvector），不再单独起库。LightRAG 自建 `LIGHTRAG_*` 前缀表，与业务 `t_*` 表互不干扰。

> 图谱通道是**增强而非必须**：后端默认 `rag.graph.type=none`，不启用本栈时行为零变化。

## 前置条件

- 宿主机 PostgreSQL 可访问，且已安装 `pgvector`（与后端共用，库名默认 `ragent`）
- 已准备百炼与 SiliconFlow API Key
- 已安装 Docker / Docker Compose

Compose 从同目录 `.env` 读取本地参数；仓库只提交 `.env.example`，实际 `.env` 已加入 `.gitignore`。默认 PostgreSQL 配置与项目 `application.yaml` 一致：`postgres/postgres`、数据库 `ragent`。

## 启动

```bash
cd resources/docker/graphrag
cp .env.example .env
# 编辑 .env，填写 BAILIAN_API_KEY 与 SILICONFLOW_API_KEY
docker compose -f lightrag-neo4j-stack.compose.yaml up -d
```

### 在 IDEA 中启动

1. 在 `resources/docker/graphrag` 目录将 `.env.example` 复制为 `.env`，填写两个 API Key
2. 打开 `lightrag-neo4j-stack.compose.yaml`
3. 点击编辑器左侧 Compose 的启动按钮，直接启动整个栈

Docker Compose 默认把首个 Compose 文件所在目录作为项目目录，因此会自动加载旁边的 `.env`。如果使用的是手工创建且修改过项目目录的 IDEA Run Configuration，可在 `Run | Edit Configurations` 中为该 Docker Compose 配置选择此 `.env` 文件。

> `.env` 的自动加载只适用于这个 Docker Compose 启动。IDEA 中单独启动 Spring Boot 时，如需同一组 API Key，还要在 Spring Boot Run Configuration 的 Environment variables 中选择该 `.env`。

首次拉起会构建本地 `ragent/neo4j-gds:5.26-2.13.11` 镜像，并下载一次约 62 MB 的 GDS JAR；该步骤可能需要一到几分钟。GDS 随后固化在镜像层中，普通启动与重启不再访问插件下载站。`docker compose ps` 两个服务均 `healthy` 后即就绪。

## 验证

| 检查项 | 方式 |
|--------|------|
| LightRAG 存活 | `curl http://localhost:9621/health` 返回 `{"status":"healthy",...}` |
| LightRAG WebUI | 浏览器打开 http://localhost:9621/webui |
| Neo4j Browser | 浏览器打开 http://localhost:7474 （账号密码见 `.env` 的 `NEO4J_USERNAME` / `NEO4J_PASSWORD`） |
| 写入连通 | `curl -X POST http://localhost:9621/documents/text -H 'Content-Type: application/json' -d '{"text":"张三是某公司CEO，公司总部在杭州。","file_source":"smoke_test"}'`，稍候在 Neo4j Browser 执行 `MATCH (n) RETURN n LIMIT 25` 可见抽取出的实体与关系 |

## 对接后端

栈就绪后，在后端 `application.yaml` 打开图谱通道并重启：

```yaml
rag:
  graph:
    type: lightrag                        # 由 none 改为 lightrag，注册 LightRagClient + GraphSearchChannel
    lightrag:
      base-url: http://127.0.0.1:9621     # 本机默认；后端与本栈不同机时改为对应地址
      query-mode: hybrid                  # 只回图结构证据，与向量 / 关键词通道正交（可选 naive / local / global / hybrid / mix）
    embedding-model: qwen-emb-8b          # 与 Compose 的 SiliconFlow Embedding 配置一致
    ingestion:
      async: true
      global-workspace: false
  search:
    channels:
      graph:
        enabled: true                     # 检索时并入图谱通道
        mode: both                        # intent 命中 KB 才走 / global 全量走 / both 都走
        top-k-multiplier: 2
```

后端一侧无需 api-key：本地不启用鉴权，`rag.graph.lightrag` 无 `api-key` 字段，客户端不发送鉴权头。

开启后，向量写入经装饰器 `GraphSyncingVectorStoreService` 自动同步图谱（摄取 / 重建 / 删除全路径覆盖），删除知识库时 MQ 消费者按 `{collectionName}_` 前缀清理对应图谱数据，均为 best-effort，失败不阻断主链路。

## 注意事项

- 后端 `rag.default.dimension` 与 Compose 的 `EMBEDDING_DIM` 当前均为 `1536`；`EMBEDDING_SEND_DIM=true` 会要求 SiliconFlow 返回相同维度
- **Embedding 模型或维度首次索引后不可更换**。修改前需清空 LightRAG 存储（PG 中 `LIGHTRAG_*` 表 + Neo4j 数据）再重新索引
- Compose 中的账号密码只面向本机开发，请勿把当前端口和固定密码直接用于生产或暴露到公网
- **镜像 tag 建议 pin 到已验证的具体版本**，勿长期用 `latest`（社区仓库历史上出现过超前日期版本 / 虚构模型名）
- LightRAG 与后端**共用宿主机 PostgreSQL**：卸载本栈只需 `docker compose down`，如需彻底清库再 `docker compose down -v`（删 Neo4j 数据卷）并手动清理 PG 中 `LIGHTRAG_*` 表
- 单 LightRAG 实例即单图（workspace 为实例级）；跨 KB 的隔离子图靠结果侧按 `file_path` 归属过滤实现，属后续阶段
- 内存偏紧的机器可下调 compose 中 Neo4j 的 `server_memory_heap_max__size` / `pagecache_size`

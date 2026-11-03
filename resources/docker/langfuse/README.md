# LangFuse 链路追踪（Agent 模式）

本目录提供 LangFuse v4 的本机自托管编排，用于观测 Agent 模式的执行现场：模型每一轮实际看到的完整上下文、ReAct 轮次、每次模型调用的 token 与耗时、每批工具的批区间，以及批内**每个工具各自的**起止、耗时、状态与入参出参。

## 启动

```bash
docker compose -f langfuse-stack-v4.compose.yaml up -d
```

六个容器全部 healthy 后，浏览器打开 <http://localhost:3000>，用 `admin@ragent.dev` / `admintrace` 登录，项目 `ragent-service` 已经建好。

## 组件与端口

| 容器 | 内存上限 | 宿主机端口 | 说明 |
|------|---------|-----------|------|
| langfuse-web | 1536 MB | **3000** | UI 与 OTLP 摄取入口 |
| langfuse-worker | 1 GB | 无 | 异步消费摄取队列写入 ClickHouse |
| langfuse-clickhouse | 4 GB | 无 | 观测数据主库，这套里最吃内存的一个 |
| langfuse-postgres | 256 MB | 无 | 项目、用户、密钥等事务数据 |
| langfuse-redis | 128 MB | 无 | BullMQ 队列，内部数据上限 96 MB |
| langfuse-minio | 256 MB | 无 | 摄取事件与媒体的对象存储 |

**只发布 3000 一个端口。** 上游编排会把 minio 映到宿主机 9090、ClickHouse 映到 9000/8123、Postgres 映到 5432、Redis 映到 6379，这几个在本项目里全部撞车（9090 是应用自身、9000/9001 是 milvus 栈的 rustfs、5432/6379 是应用的库）。这里把这些 `ports` 整段删掉了，容器之间走 `langfuse-net` 内部 DNS，不需要任何宿主机端口。

注意「绑 `127.0.0.1` 就不算占用」是错的，`127.0.0.1:5432:5432` 照样会和应用的 Postgres 抢端口，所以是删而不是改绑定。

起完确认一下：

```bash
docker compose -f langfuse-stack-v4.compose.yaml ps
lsof -i :9090 -i :9099 -i :5432 -i :6379 -i :9000 -i :9001
```

第二条应该只列出应用自己的进程，没有 langfuse 的容器。

## 应用侧接入

`application.yaml` 里的追踪总开关默认关闭、内容采集默认开启，密钥已经和编排里的 `LANGFUSE_INIT_*` 对齐。起栈后只需打开追踪：

```bash
AGENT_TRACE_ENABLED=true \
RAGENT_ENGINE_TYPE=agent \
java -jar bootstrap/target/ragent.jar
```

此时 Input / Output 会随 trace 一起上报，无需再额外配置内容开关。环境变量在 Spring 的松散绑定里优先于配置文件，IDE Run Configuration 也遵循同一规则。

只想看骨架（轮次、批区间、逐工具耗时与状态、token）时显式关闭内容采集：

```bash
AGENT_TRACE_ENABLED=true \
AGENT_TRACE_CAPTURE_CONTENT=false \
RAGENT_ENGINE_TYPE=agent \
java -jar bootstrap/target/ragent.jar
```

四层节点仍会成树，只是 Input / Output 不记录业务内容。

问一个会调工具的问题，Trace 立刻出现在 LangFuse 的 Tracing 页，树形是这样：

```
invoke_agent ragent          根节点带本轮问题与最终回答
├─ reasoning                 ragent.react.round_index=1
│  └─ chat                   模型输入输出
├─ tool_batch                按 callIndex 排序的批次清单与结果
│  ├─ execute_tool A         A 自己的入参、结果、状态与真实耗时
│  └─ execute_tool B         B 自己的那一份
└─ reasoning                 ragent.react.round_index=2
   └─ chat
```

轮次、callId、batchId 都在属性里而不在名字里，所以 `reasoning` / `tool_batch` 这两个名字是固定的，可以直接按名字聚合耗时。同名工具并行时靠 `gen_ai.tool.call.id` 与 `ragent.tool.call_index` 区分。按 `langfuse.session.id` 过滤即按会话过滤，它就是库里的 conversationId。

内容开关只对**新产生的** trace 生效，改完不会回填已有的那些。

## 六个容器一个都砍不掉

ClickHouse 是观测数据主库、对象存储是摄取事件的落盘位置，都是 LangFuse v4 的硬依赖。

复用应用自己的 Postgres 和 Redis 也不行：worker 用 BullMQ，要求 Redis 的 `maxmemory-policy` 为 `noeviction`，和应用 Redis 的淘汰策略一冲突就是**静默丢摄取任务**——追踪数据缺一块比没有追踪更难排查。

## 已知取舍

- **多模态附件在 UI 里预览不可用**。`LANGFUSE_S3_MEDIA_UPLOAD_ENDPOINT` 上游是 `http://localhost:9090`，靠浏览器直连 minio 取文件；我们不发布 minio 端口，只能给内部地址 `http://minio:9000`。本项目的 trace 是纯文本，不受影响。
- **批量导出走不通**，同上原因，`LANGFUSE_S3_BATCH_EXPORT_ENABLED` 保持关闭。
- **`TELEMETRY_ENABLED` 关掉了**，不往上游回传使用统计。

## minio 镜像拉不动时

`cgr.dev/chainguard/minio` 是上游用的镜像，chainguard 免费档只提供 `latest`，是这套里唯一没钉版本的一个，国内网络也可能拉不动。换成官方镜像只改一行：

```yaml
  minio:
    image: minio/minio:RELEASE.2024-12-18T13-15-44Z
```

`entrypoint`、`command`、healthcheck 都不用动——官方镜像里 `/usr/bin/mc` 和 `/usr/bin/curl` 都在，`mc ready local` 照样能跑（已实测过这个 tag）。

## 清理

```bash
docker compose -f langfuse-stack-v4.compose.yaml down          # 停容器，追踪数据保留
docker compose -f langfuse-stack-v4.compose.yaml down -v       # 连数据卷一起删
```


## 本机资源与日志配置（2026-11-02）

- ClickHouse 内存上限 4 GiB、CPU 上限 2 核；Worker 上限 1 核，Web 上限 2 核。
- `clickhouse-config/90-local-development.xml` 将后台调度池设为 32、合并池设为 8，保留 15% 内存余量。`clickhouse-users/90-local-development.xml` 将单查询线程数设为 2。
- ClickHouse 文件日志使用 `information` 级别，单文件 100 MiB，轮转保留 3 份；七张核心系统诊断日志表设置 7 天 TTL。TTL 随后台合并生效，不保证到期立刻释放磁盘。
- 指标改为每 10 秒采集；`system.metric_log` 使用 256 行的合并块、8–64 KiB 压缩块及 Wide 数据分片，降低宽表合并的内存峰值。
- 六个容器统一使用 Docker `local` 日志驱动，每份 10 MiB、保留 3 份。
- 这些限制面向本机开发环境；增加并发或数据量前重新评估 CPU、内存和日志保留期。
- 应用配置使用 `docker compose -f langfuse-stack-v4.compose.yaml up -d --pull never`，会重建配置变化的容器，命名数据卷保留。

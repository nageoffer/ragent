# Ragent AI 本地启动教程

> 适用环境：Windows / macOS / Linux，需 Docker、JDK 17+、Node.js 18+。

---

## 第一步：环境准备

确保已安装以下工具：

| 工具 | 验证命令 | 要求版本 |
|:---|:---|:---|
| Docker | `docker --version` | 20.x+ |
| JDK | `java -version` | 17+ |
| Maven | `mvn --version` | 不用装，项目自带 mvnw |
| Node.js | `node --version` | 18+ |
| npm | `npm --version` | 9+ |

---

## 第二步：克隆项目

```bash
git clone https://github.com/nageoffer/ragent.git
cd ragent
```

---

## 第三步：修复 Maven 编译错误（必做）

项目有一个致命 Bug —— `framework/pom.xml` 中 Guava 依赖缺少版本号，Maven 会直接编译失败。

打开 `framework/pom.xml`，找到第 34-37 行：

```xml
<dependency>
    <groupId>com.google.guava</groupId>
    <artifactId>guava</artifactId>
</dependency>
```

改为：

```xml
<dependency>
    <groupId>com.google.guava</groupId>
    <artifactId>guava</artifactId>
    <version>33.4.0-jre</version>
</dependency>
```

> 这是阻止编译的**第一道坎**，不改后面全是白费。

---

还需要修复一个隐藏依赖 —— Guava 的传递依赖 `org.jspecify:jspecify` 标记为 optional，不会自动传递。`ApplicationContextHolder.java` 引用了 `@NonNull` 注解，缺少这个依赖同样编译失败。

在 `framework/pom.xml` 的 Guava 依赖后面加上：

```xml
<dependency>
    <groupId>org.jspecify</groupId>
    <artifactId>jspecify</artifactId>
    <version>1.0.0</version>
</dependency>
```

> 以上两个 POM 修改经真实环境踩坑验证，缺一不可。

---

## 第四步：启动基础设施（Docker）

项目依赖 5 个外部服务：PostgreSQL、Redis、RocketMQ、Milvus（含 RustFS S3）。

### 4.1 启动 Milvus + RustFS（S3）

RustFS 提供 S3 兼容的对象存储，Milvus 依赖它，所以先起这个堆栈：

```bash
docker compose -f resources/docker/milvus-stack-2.6.6.compose.yaml up -d
```

等待健康检查通过（约 1-2 分钟）：
```bash
docker ps
```

确认这四个容器都是 `healthy` 状态：`rustfs`、`etcd`、`milvus-standalone`、`milvus-attu`。

### 4.2 启动 RocketMQ

```bash
docker compose -f resources/docker/rocketmq-stack-5.2.0.compose.yaml up -d
```

确认 `rmqnamesrv`、`rmqbroker`、`rocketmq-dashboard` 都在运行。

### 4.3 启动 PostgreSQL 和 Redis

这两个项目 docker-compose 里没带，需要自己起。最快的方式：

```bash
# PostgreSQL（必须安装 pgvector 扩展）
docker run -d --name postgres-ragent \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=ragent \
  -p 5432:5432 \
  pgvector/pgvector:pg17

# Redis
docker run -d --name redis-ragent \
  -p 6379:6379 \
  redis:7-alpine \
  redis-server --requirepass 123456
```

> 注意：必须用 `pgvector/pgvector` 镜像，普通 PostgreSQL 没有向量扩展。

---

## 第五步：初始化数据库

```bash
# 建表
docker exec -i postgres-ragent psql -U postgres -d ragent < resources/database/schema_pg.sql

# 导入初始数据（admin/admin 用户）
docker exec -i postgres-ragent psql -U postgres -d ragent < resources/database/init_data_pg.sql
```

验证一下：
```bash
docker exec -i postgres-ragent psql -U postgres -d ragent -c "SELECT username, role FROM t_user;"
```

应该看到一行 `admin | admin`。

---

## 第六步：配置 API Key（运行 AI 能力必做）

`bootstrap/src/main/resources/application.yaml` 中的默认聊天模型 `qwen3-max` 和嵌入模型都走远程 API，需要设置环境变量：

**Windows (PowerShell):**
```powershell
$env:BAILIAN_API_KEY = "你的阿里云百炼APIKey"
$env:SILICONFLOW_API_KEY = "你的SiliconFlow APIKey"
```

**macOS / Linux:**
```bash
export BAILIAN_API_KEY="你的阿里云百炼APIKey"
export SILICONFLOW_API_KEY="你的SiliconFlow APIKey"
```

> 如果没有 API Key 又不想花钱，可以改用本地 Ollama。参考文末的「可选：纯本地模式」。

申请地址：
- 百炼：https://bailian.console.aliyun.com
- SiliconFlow：https://siliconflow.cn

---

## 第七步：编译项目

```bash
./mvnw clean install -DskipTests
```

Windows 用 `mvnw.cmd`：
```cmd
mvnw.cmd clean install -DskipTests
```

看到 `BUILD SUCCESS` 继续下一步。

---

## 第八步：启动 MCP 服务端

开第一个终端：

```bash
cd mcp-server
../mvnw spring-boot:run
```

默认端口 `9099`，看到 `Started McpServerApplication` 即成功。

---

## 第九步：启动主应用

开第二个终端，回到项目根目录：

```bash
cd bootstrap
../mvnw spring-boot:run
```

默认端口 `9090`，上下文路径 `/api/ragent`。

看到 `Started RagentApplication` 且没有异常日志，后端启动成功。

---

## 第十步：启动前端

开第三个终端：

```bash
cd frontend
npm install
npm run dev
```

默认端口 `5173`，浏览器访问 http://localhost:5173

登录账号：`admin` / `admin`

---

## 第十一步：验证

1. 浏览器打开 http://localhost:5173，用 `admin/admin` 登录
2. 新建一个知识库，上传一份文档
3. 在对话框里提问，看是否能正常检索和生成回答

---

## 常见问题

### Q1: 编译报 `找不到org.jspecify.annotations包` 或 `找不到符号: 类 NonNull`

第三步 jspecify 依赖没加，回到第三步加上 `org.jspecify:jspecify:1.0.0`。

### Q2: 编译报 `dependencies.dependency.version' for guava is missing`

第三步没做，回到第三步修复 Guava 版本号。

### Q2: 启动报 `SnowflakeIdInitializer` 相关错误

Redis 没启动或密码不对。检查：
```bash
docker ps | grep redis-ragent
```
确认 Redis 密码是 `123456`（和 `application.yaml` 中一致）。

### Q3: 启动报 `Connection to 127.0.0.1:5432 refused`

PostgreSQL 没启动。检查：
```bash
docker ps | grep postgres-ragent
```

### Q4: 启动报 `relation "t_user" does not exist`

数据库表没建。回到第五步执行 SQL。

### Q5: 调用 AI 时报 401 或认证错误

API Key 没设或过期。检查环境变量：
```bash
echo $BAILIAN_API_KEY
echo $SILICONFLOW_API_KEY
```

### Q6: 知识库上传文档失败

RustFS (S3) 没启动。检查：
```bash
docker ps | grep rustfs
```

### Q7: RocketMQ 连接报错

```bash
docker ps | grep rmq
```
确认 `rmqnamesrv` 和 `rmqbroker` 都在运行。

---

## 可选：纯本地模式（不需要任何 API Key）

如果不想申请远程 API Key，可以用 Ollama 跑本地模型。

### 1. 安装 Ollama

```bash
# macOS / Linux
curl -fsSL https://ollama.com/install.sh | sh

# Windows: 从 ollama.com 下载安装包
```

### 2. 拉取模型

```bash
ollama pull qwen3:8b-fp16
ollama pull qwen3-embedding:8b-fp16
```

### 3. 修改配置

编辑 `bootstrap/src/main/resources/application.yaml`：

```yaml
# 将默认模型从远程切到本地
ai:
  chat:
    default-model: qwen3-local          # 原来是 qwen3-max
    deep-thinking-model: qwen3-local    # 原来是 qwen3-max
  embedding:
    default-model: qwen-emb-local       # 原来是 qwen-emb-8b
```

这样就不需要设 `BAILIAN_API_KEY` 和 `SILICONFLOW_API_KEY` 了。

> 注意：本地 8B 模型效果不如云端大模型，仅适合开发调试。

---

## 服务端口一览

| 服务 | 端口 | 用途 |
|:---|:---|:---|
| 主应用 | 9090 | 后端 API + SSE |
| MCP Server | 9099 | MCP 工具调用 |
| 前端 | 5173 | React 前端页面 |
| PostgreSQL | 5432 | 业务数据库 + pgvector |
| Redis | 6379 | 缓存 / 分布式 ID |
| RocketMQ | 9876 / 10909-10912 | 消息队列 |
| Milvus | 19530 | 向量数据库（pgvector 模式下不实际使用） |
| RustFS | 9000 / 9001 | S3 对象存储 |
| Ollama | 11434 | 本地模型推理 |

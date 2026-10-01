# 已启动服务的 Agent 多用户并发回归

[返回回归总览](../README.md)

本套件直接调用已经启动的 Ragent，不启动或重启服务、不替换模型、不修改提示词或数据库结构。
通过真实 HTTP/SSE 请求和只读数据库观察，分别回答三个问题：

1. **是否真的并行**：不同用户的运行位是否在同一个 Redis 原子快照中同时存在，模型输出的客户端接收时间线是否重叠。
2. **是否隔离**：回答、思考、工具输出、历史、持久状态和长期记忆是否出现其他用户的随机标记，消息与任务的归属是否一致。
3. **是否正确收尾**：同用户并发是否拒绝，取消 A 是否影响 B，运行位、任务 owner/cancel 标记和后台提取是否最终收敛。

多个 HTTP 请求同时发出、模型声称“已经查询”、请求最后返回 200，都不能单独证明以上条件。Redis 采样使用一条只读 Lua 同时观察所有测试 key，避免依次 GET 把先后执行误认为同时执行。SSE 接收时间有网络缓冲因素，不冒充模型供应商内部执行时间。

## 前提与范围

- Java 17+；Ragent、PostgreSQL、Redis、当前配置的 MCP 服务已启动。
- 默认继承 `bootstrap/src/main/resources/application.yaml` 和 `mcp-server/src/main/resources/application.yml`；支持用 `--config` 指定本地配置。环境变量 `RAGENT_REGRESSION_USERNAME` / `RAGENT_REGRESSION_PASSWORD` 覆盖创建账号所需的管理员登录。
- 每轮通过 `/users` 创建独立普通账号（默认 4 个，可选 2–12 个）。只操作这些测试账号的会话和记忆。
- 普通聊天会调用当前真实模型，产生正常模型用量。请求仅要求只读商品、知识库、本人订单/购物车查询，以及测试账号自己的记忆整理；不提交交易、售后或工单。
- 所有数据库探针只读。MCP 业务库只输出聚合行数与摘要，平台库只导出本轮测试用户/会话的数据。不批量读取其他用户聊天，不直接造库内状态。
- 测试账号与数据保留以便复核，不自动删除。运行产物、编译缓存和凭据均在本套件 `artifacts/`，由根 `.gitignore` 排除；`.credentials/` 权限为 700，文件为 600，报告不含密码或 token。

## 一键运行

从仓库根目录执行：

```bash
# 只探活，不创建用户、不发起聊天，不能产生并行通过结论
bash resources/regression/agent-concurrency/run.sh --preflight

# 编译并执行完整真实回归；产物自动放到 artifacts/时间戳-随机串/
bash resources/regression/agent-concurrency/run.sh --users 4

# 也可指定一个全新的空输出目录
bash resources/regression/agent-concurrency/run.sh \
  --users 4 --output-dir resources/regression/agent-concurrency/artifacts/my-run
```

输出目录必须为空，防止新旧账号、会话和证据混合。默认每轮硬超时 300 秒，后台收敛观察 60 秒，Redis 每 250 毫秒采样一次；在 `regression.properties` 中调整。请求流超时由独立定时器处理，不能依赖下一条 SSE 到达才发现超时。

完整流程包括：四用户普通长回答、各自历史续聊、商品/知识检索、本人订单/购物车查询，同用户同会话及新会话竞争，越权停止拒绝、定向取消与恢复、越权读取会话、并行写入长期记忆和全新会话回忆。问题均不超过服务端的 300 字限制。

完整运行结束后会自动离线分析，保留 `runner-report.md` 并生成综合 `report.md`。退出码：0 为已执行检查通过，2 为失败，3 为证据不足或关键工具未覆盖。**是否并行、隔离是否通过、业务行为是否完整需要分别阅读报告**，不能把模型漏复述一个标记解释成所有用户被串行执行，也不能把整体无报错解释为所有 SDK 功能都安全。

## 只读复查和离线报告

```bash
run_dir=resources/regression/agent-concurrency/artifacts/my-run

# 只读检查这批账号现存的数据库/Redis状态，不创建账号、不聊天
bash resources/regression/agent-concurrency/run.sh --recheck "$run_dir"

# 离线分析原始帧、请求索引、数据库与Redis证据，生成最终报告
java -cp resources/regression/agent-concurrency/artifacts/classes \
  com.nageoffer.ai.ragent.initializer.ConcurrencyReportMain --run-dir "$run_dir"

# 若补做了只读复查，可明确指定最新快照；不覆盖原始执行记录
java -cp resources/regression/agent-concurrency/artifacts/classes \
  com.nageoffer.ai.ragent.initializer.ConcurrencyReportMain \
  --run-dir "$run_dir" --snapshot-dir "$run_dir/recheck-实际时间戳"
```

支持两种已核实的状态表：当前工作区 `PgAgentStateStore` 的 `user_id/session_id/payload`，以及官方 PostgreSQL 存储的 `session_id=userId:sessionId/state_data`。后者保留原始存储键并解码归属；未知结构标为无法验证。实际存储与工作区源码不一致时，报告明确限制结论适用的运行版本，不自行迁移数据库。

## 同用户拒绝的补充诊断

若同用户竞争返回空 HTTP 500，可运行请求头对照。它复用本次账号，新增两条真实并发对话，比较严格 `Accept: text/event-stream` 与同时接受 JSON 时的同用户拒绝响应，再定向取消一条并检查另一条是否正常完成。

```bash
java -cp resources/regression/agent-concurrency/artifacts/classes \
  com.nageoffer.ai.ragent.initializer.ConcurrencyGateProbeMain "$run_dir"
```

结果在 `gate-diagnostic-*/`。补充诊断后再执行一次 `--recheck`，把新会话也纳入最终数据库观察。脚本没有修改服务配置或业务代码。

## 产物与证据

| 文件 | 内容 |
| --- | --- |
| `environment.json` | 实际 meta 响应、本地配置口径、覆盖范围、关键源码指纹 |
| `run.json`、`turns/*.json` | 用户标记、每请求问题/身份、时间线、重组回答/思考和工具状态 |
| `sse/*.jsonl` | 按时刻保存的原始帧、响应头、非 SSE 错误响应及中断证据；不含 Authorization |
| `redis-samples.jsonl` | 原子运行位、owner/cancel、TTL 采样；Redisson 值保留 hex，避免错误 UTF-8 解码 |
| `platform-*.json` | 七张 Agent 表、关联完整性、跨用户标记、抽取状态、压缩事件 |
| `business-before/after.json` | 十张 MCP 业务表和 pgvector 的行数/完整行摘要，测试账号个人业务行数 |
| `checks.json` | 运行时原始断言，离线分析不覆盖它 |
| `analysis.json`、`analysis-checks.json`、`report.md` | 离线综合判断，分别呈现并行、隔离、功能结果和未覆盖项 |
| `.credentials/*.json` | 只保存在本地忽略目录的随机测试账号凭据 |

业务表指纹相等仅表示两次快照间没有净变化，不证明不存在回滚或先改后还原。指纹变化可能来自其他客户端或后台任务，不直接归罪回归请求。摘要/压缩没有自然触发时是未覆盖，不能用空表宣称该机制通过。

## 离线自检

先运行 `run.sh --help` 编译，然后：

```bash
java -cp resources/regression/agent-concurrency/artifacts/classes \
  com.nageoffer.ai.ragent.initializer.ConcurrencySseSelfTestMain
java -cp resources/regression/agent-concurrency/artifacts/classes \
  com.nageoffer.ai.ragent.initializer.ConcurrencyReportMain --self-test
```

SSE 自检只绑定本机临时 HTTP 端口，不调用 Ragent，不创建用户；覆盖真实 cancel payload、双用户独立登录头、JSON拒绝、帧解析、缺失终态、硬超时与半帧留证。

本套件不向运行服务注入 SDK 原生动态工具组或 `ToolEmitter`，不验证它们的线程安全；也不覆盖多节点、进程重启、无界并发容量或尚未接入的其他存储后端。通过结果始终限定于本次已启动服务、实际触发的功能与观测并发度。

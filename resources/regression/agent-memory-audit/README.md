# 记忆问题真实服务复现

[返回回归与测评总览](../README.md)

这是人工复现与取证工具，复用 `resources/initializer/common` 和 `agent-memory` 的登录、SSE、JDBC 客户端，调用已经启动的服务。`COMPLETED` 表示聊天完成，退出码 0 表示采集正常结束，**都不代表业务检查通过**；需结合处理记录、有效记忆和全新会话回答判定。

已完成的真实服务结果见 [2026-09-27 实测报告](results/2026-09-27/report.md)，其中包含公开逐轮证据；账号密码不随报告留存。

## 范围

- 跨会话旧消息晚处理，是否覆盖较新的长期记忆。
- 用户通过对话修改单条、删除单条、清空全部长期记忆的实际结果。
- 删除后，尚未抽取的旧会话能否重新写入被删除的信息。
- 正常长会话是否产生真实摘要，摘要是否丢节或把草稿误记成已发送。

所有用户输入最多 300 字。每类测试通过 `/users` 创建独立普通账号；管理员只用于创建测试账号和读取环境。聊天只操作测试账号。数据库只读，不造假记忆、游标或摘要；不修改提示词、运行配置，不重启服务，不调用交易、订单或售后提交操作。

`MemoryAuditMain` 的 `create` 会在服务中新增用户。`ask` 会产生正常聊天和可能的长期记忆变更。测试账号与会话保留用于复查。

## 测试账号与重复执行

用户名使用常见英文姓名，按名字加姓氏全小写拼接，例如 `jamessmith`、`emmasmith`，不包含测试前缀、场景标签或时间戳。创建前会查询已占用的用户名（含软删除账号），从 200 个姓名组合中选择未使用的名字；候选用完时明确报错，补充姓名后再运行。账号角色仍为普通 `user`，密码独立随机生成。场景与账号的对应关系保存在 `*-identity.json` 中。

- **新一轮验证**：换新的输出目录，重新 `create`；场景标签仍可用 `summary`、`temporal` 等，生成的用户名和 `userId` 不复用。旧账号的记忆按用户隔离，不需要为了复跑先删除。
- **继续旧实验**：沿用原目录执行 `ask` 或 `snapshot`，会读取该账号已有记忆和会话，不能作为全新测试。
- **防止混入旧结果**：同一目录、同一身份已有凭据或证据文件时，`create` 会拒绝；只删掉 `.credentials` 也不会让新账号接着旧 JSONL 运行。摘要运行入口要求整个输出目录为空。
- **清理时机**：需要查库复核时保留账号；证据归档、修复验收结束后可按对应 `*-identity.json` 中的 `userId` 清理。当前用户删除接口只软删除用户，不级联清理会话、消息、记忆或抽取记录；删除后也不能复用固定用户名。因此不在回归脚本中自动删除或批量清库。

运行产物统一放在本套件 `artifacts/` 下，由仓库根 `.gitignore` 忽略；每轮验证使用新的子目录。已有 `results/` 是选择性保留的历史证据，不受该规则影响。

2026-09-27 的历史账号改名记录见 [账号改名对照](results/2026-09-27/account-renames.json)。当时采用中文姓名，用户 ID 保持不变，本地登录凭据已同步，新用户名登录验证通过。该记录、历史 `*-identity.json` 和逐轮证据保留测试当时的内容；后续新建账号采用上述英文姓名规则。

## 编译

所有入口均使用 Java 17+，不需要其他语言运行时。凭据权限设置使用 POSIX 文件权限，适用于 macOS/Linux。服务、PostgreSQL、Redis 应已启动；摘要剧本需要比特严选知识库及对应人设。连接配置沿用 `agent-memory/regression.properties`，默认继承本地 `application.yaml`。

从仓库根目录执行：

```bash
mkdir -p resources/regression/agent-memory-audit/artifacts/classes
javac -encoding UTF-8 -d resources/regression/agent-memory-audit/artifacts/classes \
  resources/initializer/common/*.java \
  resources/regression/agent-memory/*.java \
  resources/regression/agent-memory-audit/*.java
```

离线自检（不连接服务、不创建用户）：

```bash
java -cp resources/regression/agent-memory-audit/artifacts/classes \
  com.nageoffer.ai.ragent.initializer.MemoryAuditSelfTest
```

覆盖未启动/延迟启动/超时的观察、读库和文件失败时留证、聊天超时、参数校验、摘要证据过滤，以及摘要运行流程的提前停止、未覆盖、失败留档和空目录保护。

## 调用

```bash
audit_out="resources/regression/agent-memory-audit/artifacts/memory-audit-$(date +%Y%m%d-%H%M%S)"
audit_suite="resources/regression/agent-memory-audit"
audit() {
  java -cp resources/regression/agent-memory-audit/artifacts/classes \
    com.nageoffer.ai.ragent.initializer.MemoryAuditMain \
    resources/regression/agent-memory/regression.properties "$audit_out" "$@"
}
audit env
```

模式如下；`create` 需要配置有创建用户权限的账号，之后的聊天使用新建普通账号。每次测试使用新的目录与身份；创建账号请串行执行，聊天不要并行操作同一身份：

```text
env
create <身份标签>
ask <身份标签> <会话标签> <问题文件> [--require-extraction]
snapshot <身份标签>
export-summary <身份标签> <会话标签>
```

## 旧会话覆盖新纠正

同一身份的同一个会话标签会复用会话；换标签新建会话。先建立未提取的旧消息：

```bash
audit create temporal
audit ask temporal A "$audit_suite/temporal-short-a1.txt"
```

核对首轮没有调用 `flush_memory`、没有抽取台账和有效记忆；如果已经提取杭州，本次积压前提不成立，换新账号重试，不计为通过或失败。`NOT_OBSERVED` 只表示尚未观察到台账，不能单独证明不存在后台任务。确认前提后继续：

```bash
audit ask temporal B "$audit_suite/temporal-b1.txt" --require-extraction
audit ask temporal A "$audit_suite/temporal-a2.txt"
audit ask temporal A "$audit_suite/temporal-a3.txt"
audit ask temporal fresh "$audit_suite/temporal-probe.txt"
audit snapshot temporal
```

逐步核对：

1. A：`temporal-short-a1.txt`。必须检查没有调用 `flush_memory`、没有抽取台账，才满足旧消息积压前提。否则标为前提未满足，不能冒充通过。
2. B：`temporal-b1.txt`。确认南京已实际成为有效记忆。
3. A：`temporal-a2.txt`。
4. A：`temporal-a3.txt`。继续触发积压整理，核对实际入口和记忆变化；可能是前台 flush，也可能是后台提取，本次历史实测为前台 `FLUSH`。
5. fresh：`temporal-probe.txt`。新会话问题不带城市答案。

如果没有观察到整理，先查旧消息是否已被之前的提取覆盖。尚有积压却未处理时，不能仅凭暂时没有回退就宣称修复成功。

## 删除后旧消息重新写回

```bash
audit create revival
audit ask revival A "$audit_suite/revival-01-a-old.txt"
```

同样先核对首轮未提取，再执行：

```bash
audit ask revival B "$audit_suite/revival-02-b-remember.txt" --require-extraction
audit ask revival B "$audit_suite/revival-03-b-delete.txt" --require-extraction
audit ask revival A "$audit_suite/temporal-a2.txt"
audit ask revival A "$audit_suite/temporal-a3.txt"
audit ask revival fresh "$audit_suite/revival-06-probe.txt"
audit snapshot revival
```

先确认删除后有效住址记忆为空，再看返回 A 后是否新增另一条杭州记忆，以及 fresh 会话的回答。旧 A 保留聊天原文，不能仅凭 A 的回答提到杭州判断记忆复活。

## 单条修改、删除、清空

```bash
audit create management
audit ask management edit "$audit_suite/management-01-remember.txt" --require-extraction
audit ask management edit "$audit_suite/management-02-update.txt" --require-extraction
audit ask management verify-city "$audit_suite/management-03-check-city.txt"
audit ask management edit "$audit_suite/management-04-delete-city.txt" --require-extraction
audit ask management verify-deleted "$audit_suite/management-05-check-deleted.txt"
audit ask management edit "$audit_suite/management-06-clear.txt" --require-extraction
audit ask management verify-cleared "$audit_suite/management-07-check-cleared.txt"
```

确认南京替代杭州、删除城市后职业保留、清空后有效记忆是否为空。`NOOP` 可以是已结算的处理结果，但不能作为清空成功的依据。当前删除表示记录失效，不是物理删除聊天或历史数据。

## 摘要：运行、停止与导出

先查看计划，不调用服务：

```bash
java -cp resources/regression/agent-memory-audit/artifacts/classes \
  com.nageoffer.ai.ragent.initializer.MemoryAuditSummaryMain \
  --output-dir resources/regression/agent-memory-audit/artifacts/summary-audit-preview --dry-run
```

运行真实流程，输出目录必须为空：

```bash
java -cp resources/regression/agent-memory-audit/artifacts/classes \
  com.nageoffer.ai.ragent.initializer.MemoryAuditSummaryMain \
  --output-dir "resources/regression/agent-memory-audit/artifacts/summary-audit-$(date +%Y%m%d-%H%M%S)"
```

程序创建独立 `summary` 账号，逐轮发送 `summary-turns.json` 的问题；提供任务和草稿后，一旦观察到上下文摘要，就跳到 `s30-probe` 追问并导出证据。最多 29 轮铺垫加 1 轮追问，可用 `--max-turns 11` 限制成本。追问本身也可能触发摘要；没有真实摘要则记 `UNCOVERED`，不能算通过。长回答用于覆盖当前预算，不代表日常平均聊天长度。

可选 `--config /path/to/local.properties`、`--suite-dir /path/to/agent-memory-audit`；编译目录由 `java -cp` 指定，子命令复用同一 Java 运行时和 classpath。`summary-run.json` 分别记录尝试发送的 `attemptedTurns`、正常完成采集的 `collectedTurns`，以及数据集指纹；两者之差不代表聊天一定没执行，应检查该轮 JSONL。`OBSERVED` 仅表示观察到摘要，不是质量通过。程序报错即停止，不把固定“第 11 轮”当作所有环境的触发点。

最后自动执行 `export-summary summary main`，导出必要上下文文本、摘要和隔离检查，不公开完整思考内容或工具结果正文。需要再次导出时，将下方目录替换为实际运行目录：

```bash
java -cp resources/regression/agent-memory-audit/artifacts/classes \
  com.nageoffer.ai.ragent.initializer.MemoryAuditMain \
  resources/regression/agent-memory/regression.properties \
  resources/regression/agent-memory-audit/artifacts/your-summary-run export-summary summary main
```

核对实际摘要、原始任务与草稿是否离开非摘要上下文、长期记忆和工具是否提供替代答案，以及摘要和回答中的预算、配置、二手条件与“起草但未发送”。原文存在性检查不保证语义无损；未命中超长裁节分支仍写 `UNCOVERED`。

导出文件为 `<身份>-context-evidence.json`。它约定该用户、会话日志的前两轮是任务和草稿，并保留来源问答供核对；缺少来源则记 `UNKNOWN`。新版检查完整原文是否为保留正文的子串，历史部分字段使用文本块完全相等计数，二者不应混为同一指标。

## 观察状态与退出码

`status` / `chatStatus` 描述聊天；`observationStatus` 描述记忆观察：

| 状态 | 含义 |
| --- | --- |
| `SETTLED` | 观察到本会话的新抽取已结束，具体结果仍需检查台账 |
| `NOT_OBSERVED` | 短暂观察期未见新抽取，不证明以后不会开始 |
| `TIMEOUT` | 等到上限仍不满足观察条件 |
| `ERROR` / `NOT_STARTED` | 观察失败 / 未进入观察 |

`--require-extraction` 用于必须发生提取的步骤；未观察到新台账结算就返回非零。默认 `NOT_OBSERVED` 可返回 0；已见任务仍在处理而超时则返回非零。失败、放弃或 `NOOP` 都不能直接当作业务成功。

退出码：0 采集结束；1 参数、登录、文件等失败；2 聊天失败/超时；3 数据库观察失败/超时（包括聊天前）；4 会话或 latest 辅助文件保存失败。最终读库失败也保存已收到的回答、ID 和错误，不丢掉整轮。

采集器可配置毫秒值：`audit.chat-timeout-millis`（600000）、`audit.observation-timeout-millis`（45000）、`audit.poll-interval-millis`（1500）、`audit.discovery-window-millis`（3000）。这些不修改服务策略。

聊天超时限制客户端等候，不保证服务端已取消；观察超时是轮询预算，单次数据库查询仍受现有 `database.statement-timeout-seconds` 约束。超时后先核对台账，不自动重发同一轮问题。

## 留存证据

输出目录包含每个身份的 `*-turns.jsonl`、`*-sessions.json`、`*-identity.json` 和 `*-latest.json`。逐轮保存原问题、完整回答、工具名、开始结束时间、即时状态和等待后台抽取后的状态。

`results/2026-09-27/` 是修整采集器之前的真实记录。原始 JSONL 保留原样，不事后补写新版状态；本次工具修复不代表重新跑过业务流程。完整摘要状态备份在本地 `temp/`，公开目录改留精简证据。公开证据应选择性导出，不整包复制凭据、重复日志与完整运行状态。

随机测试密码只放在输出目录 `.credentials`，目录权限 700、文件权限 600；输出目录请保持在已忽略的 `artifacts/` 下。不要提交或分享 `.credentials`，公开报告不含令牌和密码。

数据库观测数组的列顺序：

- `memories`：ID、内容、来源、失效时间、替代者 ID、创建时间。
- `extractions`：ID、会话 ID、起始消息 ID、结束消息 ID、状态、决策数量、尝试次数、创建时间。
- `compactions`：会话 ID、代数、摘要字符数、压缩前字符数、压缩后字符数、摘要正文、创建时间。

`environment.json` 中的模型与阈值是本地 YAML 声明，不冒充 JVM 运行期覆盖值。`resolved-memory-prompts.json` 是当时实际 Redis 解析缓存里的记忆提示词（若缓存存在）。摘要审计表只存验收后文本；缺小节但无裁节日志时，不能确定是模型漏写还是程序裁掉。

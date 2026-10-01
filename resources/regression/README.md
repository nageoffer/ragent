# Ragent 回归与测评

用于验证 Ragent 核心机制和已启动的服务，覆盖记忆行为、机制正确性、质量与用量指标，以及具体问题的真实复现。

| 子目录 | 主要用途 | 适用场景 |
| --- | --- | --- |
| [agent-memory](agent-memory/README.md) | 行为回归、机制回归、单会话排查 | 修改裁剪、摘要或长期记忆逻辑后检查功能与状态 |
| [agent-memory-evaluation](agent-memory-evaluation/README.md) | 数据集测评、指标统计、A/B 报告 | 比较方案、模型或配置的质量、用量与耗时 |
| [agent-memory-audit](agent-memory-audit/README.md) | 独立账号的真实流程复现、逐轮证据采集 | 核实跨会话覆盖、删除回写、清空和摘要问题 |
| [agent-concurrency](agent-concurrency/README.md) | 多用户并发、请求隔离与数据库一致性回归 | 验证共享 Agent 的真实服务路径能否并行执行 |
| [agent-run-gate-audit](agent-run-gate-audit/README.md) | Java 测试与临时 Redis 闸门检查 | 验证会话互斥、用户名额、跨线程释放和看门狗回收 |

各套件的运行条件、命令、配置、数据操作范围和结果解释，见对应子目录的 README。

## 通用约定

- Java CLI 复用 [initializer/common](../initializer/common) 的配置、HTTP 与 JDBC 客户端；需要初始化测试知识库时，使用 [初始化工具](../initializer/README.md)。
- 每个直接子套件使用自己的 `artifacts/` 保存编译缓存、运行日志、数据库快照、报告和本地凭据；每次运行另建唯一子目录。根 `.gitignore` 的 `/resources/regression/*/artifacts/` 统一忽略这些产物，也覆盖新增套件。
- `artifacts/` 按需创建，不追踪空目录。已有 `results/` 历史证据保留；如需提交新的证据，从产物中选择性导出并去除凭据，不强制添加整个 `artifacts/`。
- 以实际观测与断言判断结果：未触发的 `UNCOVERED`、观测不完整的 `UNKNOWN`，都不能当作通过。

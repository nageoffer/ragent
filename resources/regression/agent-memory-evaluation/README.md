# Agent 记忆测评

[返回回归与测评总览](../README.md)

基于 `resources/initializer/bit-selection` 的知识库提问，测裁剪、摘要、缓存和多轮记忆效果。Java 17 编译后运行。

先确认已经初始化比特严选数据、启用比特严选人设，并启动服务与数据库。程序只提问和读取测评结果，不负责初始化或重启服务。

本套件的编译缓存、测评报告与原始采集结果放在 `artifacts/`，由仓库根 `.gitignore` 忽略。每次测评显式指定新的 `--output-dir`，避免混用结果。

## 1. 编译并跑用例

在仓库根目录执行：

```bash
mkdir -p resources/regression/agent-memory-evaluation/artifacts/classes
javac -encoding UTF-8 -d resources/regression/agent-memory-evaluation/artifacts/classes \
  resources/initializer/common/*.java \
  resources/regression/agent-memory/*.java \
  resources/regression/agent-memory-evaluation/*.java

java -cp resources/regression/agent-memory-evaluation/artifacts/classes \
  com.nageoffer.ai.ragent.initializer.AgentMemoryEvaluationMain \
  --limit 1 \
  --variant baseline \
  --output-dir resources/regression/agent-memory-evaluation/artifacts/bit-evaluation/baseline
```

默认读取展开格式的 `datasets/bit-selection.json`。`--limit 1` 先跑第一个短用例；去掉它跑整份数据。每个用例新建会话，用例内按顺序提问。再次运行时换一个输出目录，程序不会覆盖旧结果。

连接和主模型默认继承 `application.yaml`，修改 `agent.chat.model` 后测评自动跟随，无需另写模型名。其他环境用 `--config /path/to/local.properties`，账号可通过 `RAGENT_EVAL_USERNAME`、`RAGENT_EVAL_PASSWORD` 设置。服务若使用 JVM 参数覆盖配置，测评配置也需同步对应项。

| 可选参数 | 用途 |
| --- | --- |
| `--dataset 路径` | 换成自己的 JSON 数组数据集 |
| `--cases 用例ID` | 只跑指定用例，多个 ID 用逗号分隔 |
| `--tags lifecycle` | 只跑带指定标签的用例 |
| `--limit 2` | 取筛选后的前两个完整用例 |
| `--repetitions 3` | 每个用例新建会话，重复三次 |
| `--dry-run true` | 只查看运行计划，不请求服务 |

用例内容、对应知识文档和新增方法见 [datasets/README.md](datasets/README.md)。用例只询问知识和历史事实，不请求下单、改单或售后申请。

## 2. 看结果

打开输出目录里的 **`report.md`**，先看：

| 指标 | 怎么看 |
| --- | --- |
| 错误数、断言通过率 | 请求是否完成，指定知识点或历史事实是否答对；没有断言的轮次不算通过 |
| 裁剪块数、净回收字符 | 实际替换了多少工具结果，扣除占位后省了多少空间 |
| 摘要次数、素材压缩率 | 是否实际生成摘要，以及素材字符缩短的比例 |
| 输入 / 命中 / 未命中 tokens | 总输入已经包含命中部分，不能重复相加 |
| 调用耗时 | 比较整轮响应时间；不是首 token 时间 |

`UNCOVERED` 表示没触发，`UNKNOWN` 表示没观测完整，`N/A` 表示没有可计算的数据。查看具体哪轮答错，用 `turns.csv`；原始问答、断言明细在 `turns.jsonl`，配置和数据指纹在 `run.json`。

默认 10 万字符预算下，短用例通常不触发裁剪或摘要。验证这两层时，可以在测试服务把 `agent.memory.context-window-chars` 设为 `8000`、开启摘要，再跑长用例；修改测评文件本身不会改变服务参数。比较策略时保持两边预算相同，并关闭测试服务的长期记忆，避免跨会话事实影响结果。

## 3. 比较改动前后

同一份数据、同一子集分别跑两次，使用不同的 `--variant` 和输出目录。`variant` 只是报告标签，服务的代码或配置需要先实际切换。

```bash
java -cp resources/regression/agent-memory-evaluation/artifacts/classes \
  com.nageoffer.ai.ragent.initializer.EvaluationReportMain \
  --baseline resources/regression/agent-memory-evaluation/artifacts/bit-evaluation/baseline \
  --candidate resources/regression/agent-memory-evaluation/artifacts/bit-evaluation/candidate \
  --output-dir resources/regression/agent-memory-evaluation/artifacts/bit-evaluation/comparison
```

看 `comparison/compare.md`。程序会检查两次的数据、执行顺序和计量条件是否一致。用于项目或简历时，保留数据集、样本数、基线与这份报告；字符压缩比例不能直接当成费用节省比例。

## 费用统计（可选）

日常跑用例不需要 Gateway。默认可看主模型 token、缓存和压缩效果；摘要调用的 token 不在会话 state 中，完整费用显示未知。

需要估算主模型费用时加 `--prices 价格文件`，格式见 `prices.example.json`，其中价格只是历史示例。若要把摘要费用也算上，可用 `EvaluationUsageGatewayMain` 采集全部供应商用量，再传 `--usage-log`；运行 `EvaluationUsageGatewayMain --help` 查看用法。没有完整采集时，不把缺失费用当成零。

## 历史工具迁移

旧裁剪缓存 A/B 脚本已由本目录接管。2026-09-26 的结果、脚本和恢复补丁原样归档到本机 `temp/retired-trim-cache-ab-20260927/`，供历史查阅；归档中的版本状态只描述当时的测试结束状态，`temp/` 不纳入版本控制。

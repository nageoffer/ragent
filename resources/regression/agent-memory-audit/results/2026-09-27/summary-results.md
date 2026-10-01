# 摘要真实服务验证结果

本次未复现摘要裁节或草稿状态失真。真实发生一代摘要后即做回忆探针并停止；没有修改服务配置、业务代码、提示词或数据库状态。原先提出的超长裁节分支本次未覆盖，不能把这一次通过外推成该分支安全。

## 执行范围

- 独立测试用户：summary，用户信息见 summary-identity.json。
- 会话：2104134491064102912。
- 实际12轮：剧本s01至s11，加s30探针；单条输入最大148字符。
- 调用现有 /agent/v1/chat；只咨询、起草，不下单、不修改订单、不提交售后。
- 为触发10万字符预算，使用连续详细商品咨询；这是长会话覆盖，不代表日常平均会话长度。
- 全部12轮完成。每轮观察到的长期记忆条目数均为0，排除了本次问答从已保存长期记忆取值的干扰。

## 真正发生的压缩

第10轮结束上下文约81909字符；第11轮用户请求开始时成功压缩。

| 字段 | 值 |
|---|---:|
| generation | 1 |
| material_msg_count | 39 |
| material_chars | 60010 |
| context_chars_before | 81869 |
| context_chars_after | 23746 |
| summary_chars | 1764 |
| 小节数 | 7 |

81909是回归SQL近似字符口径；81869等审计字段为服务端AgentContextChars口径，二者差异不表示丢消息。

七节为：用户诉求、待办、下一步、当前进度、工具与发现、走不通的路、已完成。

摘要中保留了：

- “整套预算12000元给学生选Mac，必须16GB内存和512GB存储”。
- “不接受二手，除非我明确同意”。
- “不下单、不修改订单、不提交售后，只咨询”。
- “起草一封给客服的咨询邮件草稿，询问MacBook Air配置、总价、教育优惠资格与发票要求，未发送”。

## 原始消息确实离开近期上下文

- summary-evidence.json 的 originalDraftQuestionExactOccurrencesInState 为字符串 "0"：通过只读SQL对上下文text块与原始s02问题全文做完全相等匹配。
- summary-results.json 的 originalDraftQuestionExactOccurrencesInState 为数值0；originalSeedQuestionExactOccurrencesInState也为0。
- [summary-context-evidence.json](summary-context-evidence.json) 是从当时保存的原始 Agent 状态和逐轮日志离线生成的精简证据。保留用户/助手原文、摘要、工具名称及原文留存检查，移除了 thinking、工具入参/结果和其他运行字段；原始状态已备份到本地忽略目录。此次整理没有重新调用服务。
- summary-turns.jsonl 每轮before/immediate/after保留数据库观察；每轮 memories 都为空。

因此本次最终回忆有摘要来源证据，不只是“答对了”。

## 最终回答

按本次会话（含早期对话摘要）能确认的，逐条如下：

**1. 给客服的邮件状态**
处于**已起草、未发送**的状态。这封邮件的内容是咨询 MacBook Air 的配置、总价、教育优惠资格与发票要求。需要说明的是：摘要只保留了"草稿已写、没有发出"这个状态，**邮件正文的完整措辞与具体问法已不在本次上下文中，无法逐句复述**。

**2. 整套预算与最低配置**
- 整套预算：**12000 元**。
- 最低配置（针对给学生选 Mac）：**16GB 内存 + 512GB 存储**，且这两项是硬性要求——内存是底线，存储也是底线，不是"够用就行"的可选项。
- 当时的结论：在这个预算与配置条件下，**MacBook Air（13 或 15 英寸）是唯一符合条件的在售 Mac 笔记本**。MacBook Neo 因全系仅 8GB 统一内存被排除；Mac mini 虽有 16GB 内存，但属台式主机、需另配显示器键鼠，不符合笔记本需求。

**3. 什么条件下可以接受二手**
**默认不接受二手，唯一例外是你明确同意**。也就是说，二手不是一个可以自行斟酌的选项，必须经你本人点头。

**4. 关于早期信息是否保留**
以上四项都在本次会话的保留范围内，可以确认。但有几类细节**无法确认**：邮件草稿的完整正文、当时是否已选定具体的容量/颜色或下单编码、以及任何未出现在本次对话里的沟通细节。这些我没有依据，不能凭印象补。

另外按你的要求，这一轮**没有调用任何工具**，以上全部来自本次对话内容。

本轮工具列表：[]。

## 证据边界与建议

运行服务PID33303的stdout/stderr是PIPE，没有找到本次服务文件日志；scripts/logs内文件是5月旧日志，未采用。本次未拿到模型压缩前的原始摘要输出，也没有服务端“按小节边界截断”日志。

可以确认最终摘要只有1764字符、七节完整、关键限制和未发送状态保留；不能因此证明超长裁节分支永不触发。本次结果不足以把此前构造出的裁节风险定为当前已复现、必须马上修改的故障。建议先保留这组回归及超长分支的机制用例；若要加防御性保护，应标明是边界加固，不宣称本次线上流程复现了丢节。

## 文件

- summary-turns.jsonl：完整12轮问答及数据库观察。
- summary-observations.csv：逐轮字符数、代数和耗时。
- summary-evidence.json：只读查询的摘要审计与原始草稿离场计数。
- [summary-context-evidence.json](summary-context-evidence.json)：历史状态的白名单导出，包含原始状态 SHA-256 和来源说明。
- summary-results.json：便于汇总的结构化结果。
- resources/regression/agent-memory-audit/summary-turns.json：独立完整剧本。

## 证据导出格式说明

本目录的 `summary-evidence.json`、`summary-isolation.json`、`summary-results.json` 是当次执行留下的旧格式结果，保持原样。新增 `summary-context-evidence.json` 使用当前 `MemoryAuditSummary` 的白名单规则离线生成，未伪装成新的数据库观察；其中 `sourceChecks` 的 `PRESENT` / `ABSENT` 检查原始问题和回答是否作为完整子串存在于保留的用户/助手非摘要文本中，`UNKNOWN` 表示材料不足。旧格式有完全相等计数，新格式有子串检查，二者口径并不相同。

今后按根 README 的摘要流程运行，最后调用 `export-summary` 即可重新生成这种最小证据。`OBSERVED` 只表示在状态中观察到摘要，不代表语义通过；没有摘要为 `UNCOVERED`，缺少状态或对应日志为 `UNKNOWN`。源文本缺席也不能排除同义改写仍留在上下文，应结合保留的正文人工复核。

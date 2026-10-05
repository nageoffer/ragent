# 追踪属性取舍

OTel 语义约定里同一份事实经常有两三种写法，全写上去只会在后端留下重复键。2026-09-10 在本机 Langfuse 上逐条实测后砍了三项，理由和量法记在下面。

想删别的属性之前先照末尾那套跑一遍。

## 已删除

| 属性 | 理由 |
| --- | --- |
| `langfuse.observation.model.name` | 和官方中间件写的 `gen_ai.request.model` 落同一 span、喂同一列，任意一把键都能填满 |
| `gen_ai.conversation.id` | 和 `langfuse.session.id` 同源同值，都取 `ctx.getSessionId()` |
| 批 span 上的三个 OTel span event | Langfuse 摄取时整个丢弃，落库后查不到也过滤不了 |

三个 event：`ragent.tool.awaiting_confirmation`、`ragent.tool.denied`、`ragent.tool.short_circuited`。随它们一起删的还有 `RagentAttributes` 的 `TOOL_MASKED_BY`、`ERROR_SOURCE_SKILL_MASKED`，和 `AgentErrorTypes` 的 `TOOL_MASKED`、`PRE_EXECUTION_ERROR`。后两个枚举值只在 event 属性上出现，遮蔽的调用在 `McpToolProxy.callAsync` 就返回了，走不到 `AgentToolBodyTracer.trace`，没有第二条路产出这两个值。

删 event 不丢信息——同一批工具的逐条状态由 `AgentTraceEnrichmentMiddleware` 写进批 span 的 `langfuse.observation.output`：

```json
[{"callIndex":6,"name":"meeting_room_book","state":"awaiting","executed":false},
 {"callIndex":7,"name":"meeting_room_book","state":"awaiting","executed":false},
 {"callIndex":8,"name":"search_knowledge","state":"awaiting","executed":false}]
```

哪几条在等确认、跑没跑都在，而且是属性能进索引。遮蔽的技能码也还在 `shortCircuitDetail()` 上。

## 不许跟着删的

`observe()` 里三行状态赋值是 `ragent.tool.batch.outcome` 的唯一来源：

```java
outcome.awaiting = true;                 // RequireUserConfirmEvent
outcome.denied = true;                   // AllToolsDeniedEvent
outcome.member(...)                       // 短路调用要跳过，混批里另有人被拒时会报错整批结局
```

删 event 时只删了 `emit(...)` 调用，分支本身承重。真实数据里 awaiting 批出现 6 次、批级结局全对，靠的就是这三行。

`span.recordException` 也进不了索引，但它是异常栈唯一载体，一行而已，留着。

`AgentToolBodyTracer.trace()` 不是纯观测——里面的 `facts.markStarted / markEnded / markTerminated / markReturned` 有两个业务读者：`AgentStreamEventBridge` 拿去填 SSE 块和落库字段，`AgentToolBatchMiddleware` 拿 `startedAt()` 判断调用有没有真进过工具体。方法体不能绕过，调用顺序不能改。

## 看着重复但要留的

`gen_ai.tool.call.arguments` 与 `gen_ai.tool.call.result` 在真实数据里查不到，容易当死代码。实际是被 `langfuse.observation.input / output` 遮住了——单独送这两把键也能把 input / output 列填满（见 probe_c）。而且两族键内容不等价：`langfuse.observation.output` 走 `toolOutcome()`，成功失败都带正文；`gen_ai.tool.call.result` 走 `toolResult()`，按规范只在成功时写。留着相当于一份备份。

`AgentRunTracer.writeIdentity()` 每个 span 都写一遍 user 和 session 不是冗余——Langfuse 不回填父节点的这两项，漏写的那一层在 observation 级过滤里查不到。官方文档也是这么要求的。

`gen_ai.provider.name`、`gen_ai.request.stream`、`gen_ai.tool.type`、`ragent.tool.span_scope`、`ragent.contract.version` 五个取值恒定，各一行。前三个 GenAI 规范必填，后两个给查询侧留的判别位。区分度为零但也不占什么。

## 怎么量的

Langfuse v4 的 events_only 模式下 `/api/public/traces`、`/api/public/observations` 全返回 "not available"，UI 也不方便逐属性看。直接查 ClickHouse：

```bash
docker exec langfuse-clickhouse clickhouse-client -q "
SELECT k AS metadata_key, count() AS n, uniqExact(v) AS distinct_vals
FROM events_full ARRAY JOIN metadata_names AS k, metadata_values AS v
GROUP BY k ORDER BY k"
```

`events_full` 是强类型表。被认识的属性进 `input` / `output` / `provided_model_name` / `usage_details` / `session_id` 这些列，不认识的落到 `metadata_names` / `metadata_values` 数组里，两边都没有的就是被丢了。原始 OTLP 载荷在 MinIO，路径见 `blob_storage_file_path` 列：

```bash
docker exec langfuse-minio cat /data/langfuse/<path>/xl.meta | strings
```

区分"我们没写"和"写了但被丢"就靠这一步。

判断一把键有没有用不能看它在现有数据里出不出现，因为受同 span 上其它键遮蔽影响。做法是伪造一条只带这把键的 span 打进去，看列填不填：

```bash
curl -X POST http://localhost:3000/api/public/otel/v1/traces \
  -H "Authorization: Basic $(printf 'pk-lf-ragent-local:sk-lf-ragent-local' | base64)" \
  -H "x-langfuse-ingestion-version: 4" -H "Content-Type: application/json" \
  -d '{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"probe"}}]},
       "scopeSpans":[{"scope":{"name":"probe"},"spans":[{
         "traceId":"aaaa0000bbbb1111cccc2222dddd3333","spanId":"1111111111111111",
         "name":"probe_c","kind":1,"startTimeUnixNano":"...","endTimeUnixNano":"...",
         "attributes":[{"key":"langfuse.observation.type","value":{"stringValue":"tool"}},
                       {"key":"gen_ai.tool.call.arguments","value":{"stringValue":"{\"PROBE\":1}"}}]}]}]}]}'
```

用一个专门的 `service.name` 隔开，不影响正常数据。四条结论：

- 只送 `gen_ai.request.model` → `provided_model_name` 列填上
- 只送 `langfuse.observation.model.name` → 也填上了，两把键等价
- 只送 `gen_ai.tool.call.arguments` → `input` 列填上
- 两族键同时送 → `input` 取 `langfuse.observation.input` 的值，`langfuse.*` 优先

span event 那条另有三个独立证据：伪造带 event 的 span 打进去 `metadata_names` 里一个没有；官方属性映射表里 event 没有目的地；真实数据里 awaiting 发生过 6 次、`observe()` 同一个 `if` 分支里属性落了 event 没落，MinIO 原文里 `ragent.tool.awaiting_confirmation` 明明在。

删完用 `LangfuseReadbackProbe` 复查：

```bash
mvn -pl agent test -Dtest=LangfuseReadbackProbe
```

打一棵完整的树到本机 Langfuse，`service_name` 是 `ragent-service-plan-d-probe`。等入库后按 service 分组比：

```bash
docker exec langfuse-clickhouse clickhouse-client -q "
SELECT service_name,
  countIf(has(metadata_names,'attributes.langfuse.observation.model.name')) AS model_name_key,
  countIf(has(metadata_names,'attributes.gen_ai.conversation.id'))          AS conv_id_key,
  countIf(session_id!='') AS session_ok, count() AS total
FROM events_full WHERE service_name IN ('ragent-service','ragent-service-plan-d-probe')
GROUP BY service_name"
```

老数据两把键分别 17 和 28，探针那边都是 0，`session_id` 两边满的——键删了，会话身份没受影响。

有一处探针管不着：它给 `ModelCallInput` 传的 model 是 null，也没套官方中间件，模型名列前后都是空。模型列的结论靠真实数据 17 个 GENERATION span 两把键同时在场（`has_both = 17`）加上面的单键探针。要端到端验，得 `agent.trace.enabled=true` 起实例跑一轮对话。

## 纯重构怎么证明没改行为

同一天还合并了几处复制粘贴（`onActing` 的两个同体收尾 lambda、`argumentKey` 与 `callKey`、`describeFinal` 与 `describeMessage` 的公共三行）。这类改动单测全绿不算证完——改的是收尾路径，得看真发出去的字节。

办法是同一个探针跑两遍，中间夹着改动，然后按节点比取值数：

```bash
docker exec langfuse-clickhouse clickhouse-client -q "
SELECT name, type, uniqExact(input) AS distinct_in, uniqExact(output) AS distinct_out, count() AS n
FROM events_full WHERE service_name='ragent-service-plan-d-probe'
GROUP BY name, type ORDER BY name"
```

每类节点的取值数应当等于「单次运行里该类节点的不同内容数」，而不是它的两倍。这一轮的结果是 `tool_batch` 与 `invoke_agent` 各 1、`chat` 与 `leave_submit` 各 2（一轮里本来就有两条内容不同的），即两次运行逐字节相同。

顺带确认了 `describeRoleAndBlocks` 真被跑到：AGENT 节点的 output 里 `final` 键在，`shouldKeepFinal` 没把它判掉。

## 什么情况下要改回来

上面的前提是只往 Langfuse 报。换 Jaeger、Tempo、Grafana 这类后端的话 span event 是一等公民，`ragent.tool.short_circuited` 上的 `masked_by` 和逐条 error source 正常显示，那三项就该加回去。换之前先在新后端上量一遍。

`gen_ai.tool.call.arguments / result` 反过来——正因为可能换后端才留着，规范键换谁都认。

## 给工具配重试之前

`AgentToolBodyTracer.trace()` 包在 `ToolExecutor.applyRetry` 的内侧——四把工具的调用点都是 `return AgentToolBodyTracer.trace(this, param, () -> ...)`。重试即重订阅，`Mono.deferContextual` 的体会重跑一遍，于是同一次工具调用建出第二个 `execute_tool` span。起止时刻更麻烦：`facts.markStarted/markEnded` 用的是 `compareAndSet(0L, ...)`，第二个 span 会套用第一次尝试的区间，时间轴上两个节点重叠。

现在不触发，因为全项目没有一处配 `ExecutionConfig.maxAttempts`，而 `applyRetry` 在 `maxAttempts == null || <= 1` 时直接原样返回。哪天要给工具配重试，先把这个 span 的重订阅处理掉再配。

模型侧的重试不归这个包管，但别当它不存在。有效配置由三段拼出来：

- `OpenAIChatModel.Builder.build()` 调 `ModelUtils.ensureDefaultExecutionConfig`，把 `ExecutionConfig.MODEL_DEFAULTS` 装进模型的 `defaultOptions`：5 分钟超时、3 次尝试、`retryOn` 取 `RETRYABLE_ERRORS`
- `ReActAgent.buildGenerateOptions()`（`ReActAgent.java:3631`）每次调用另建一份 `executionConfig`，只填 `maxAttempts(modelConfig.maxRetries())`，其余留空
- `OpenAIChatModel.java:109` 是 `mergeOptions(options, configuredOptions)`：调用级是 primary，模型默认是 fallback，`mergeConfigs` 逐字段取非空

所以真正生效的是**调用级的尝试次数 + 模型默认的其余各项**。`agent.max-retries` 早先配 2，跑的就是「2 次尝试，仅对 429 / 5xx / `TimeoutException` / `IOException` 重试」——既不是 `MODEL_DEFAULTS` 那个 3，也不是所有异常。

**要关只能改 `agent.max-retries`。**给 `OpenAIChatModel.defaultOptions` 配 `maxAttempts(1)` 是无效的：它是 fallback，被调用级那份原样盖掉，改了等于没改。还要留意这个字段名叫 retries、值却直接喂给 `maxAttempts`，语义是「含首次的总次数」，且 `ModelConfig` 校验必须大于 0，所以 1 就是「不重试」的地板值。

要紧的是 `retryWhen` 加在整条 `doStream0` 之上，位置在 `onModelCall` 之下，不管流已经吐过多少 chunk，出错就重订阅。断网、读超时、半程 5xx 都在可重试之列，于是下游收到「前半截 + 完整第二遍」——累加的不只是本中间件的缓冲，还有框架的 `transformedText` 与 `ReasoningContext`、SSE 增量、落库正文。追踪如实反映了框架手里那份已被污染的状态，**改这个包等于把证据擦掉**。现已把 `agent.max-retries` 降到 1 关掉，等哪天实现「仅在首个 chunk 之前允许重试」再放开。

## 遗留

`AgentToolExecutionFacts.shortCircuitDetail()` 现在只有测试读，生产侧没读者了（原来是 `emitShortCircuit`）。写入方是 `McpToolProxy` 的遮蔽分支，属于业务路径。留还是删是那个类自己的事，不在这个包管。

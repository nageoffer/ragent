# 追踪属性取舍

OTel 语义约定里同一份事实经常有两三种写法，全写上去只会在后端留下重复键。这里记的是看着像冗余、其实不能删的几处，以及换后端或配重试前要先处理的事。想删属性之前先在 Langfuse 上确认它确实没有读者。

## 不许跟着删的


`observe()` 里三行状态赋值是 `ragent.tool.batch.outcome` 的唯一来源：

```java
outcome.awaiting = true;                 // RequireUserConfirmEvent
outcome.denied = true;                   // AllToolsDeniedEvent
outcome.member(...)                       // 短路调用要跳过，混批里另有人被拒时会报错整批结局
```

这几个分支本身承重，批级结局靠的就是这三行。

`span.recordException` 也进不了索引，但它是异常栈唯一载体，一行而已，留着。

`AgentToolBodyTracer.trace()` 不是纯观测——里面的 `facts.markStarted / markEnded / markTerminated / markReturned` 有两个业务读者：`AgentStreamEventBridge` 拿去填 SSE 块和落库字段，`AgentToolBatchMiddleware` 拿 `startedAt()` 判断调用有没有真进过工具体。方法体不能绕过，调用顺序不能改。

## 看着重复但要留的


`gen_ai.tool.call.arguments` 与 `gen_ai.tool.call.result` 在真实数据里查不到，容易当死代码。实际是被 `langfuse.observation.input / output` 遮住了——单独送这两把键也能把 input / output 列填满。而且两族键内容不等价：`langfuse.observation.output` 走 `toolOutcome()`，成功失败都带正文；`gen_ai.tool.call.result` 走 `toolResult()`，按规范只在成功时写。留着相当于一份备份。

`AgentRunTracer.writeIdentity()` 每个 span 都写一遍 user 和 session 不是冗余——Langfuse 不回填父节点的这两项，漏写的那一层在 observation 级过滤里查不到。官方文档也是这么要求的。

`gen_ai.provider.name`、`gen_ai.request.stream`、`gen_ai.tool.type`、`ragent.tool.span_scope`、`ragent.contract.version` 五个取值恒定，各一行。前三个 GenAI 规范必填，后两个给查询侧留的判别位。区分度为零但也不占什么。

## 什么情况下要改回来


上面的前提是只往 Langfuse 报。换 Jaeger、Tempo、Grafana 这类后端的话 span event 是一等公民，`ragent.tool.short_circuited` 上的 `masked_by`、逐条 error source 这类 span event 能正常显示，值得加回来。换之前先在新后端上量一遍。

`gen_ai.tool.call.arguments / result` 反过来——正因为可能换后端才留着，规范键换谁都认。

## 给工具配重试之前


`AgentToolBodyTracer.trace()` 包在 `ToolExecutor.applyRetry` 的内侧——四把工具的调用点都是 `return AgentToolBodyTracer.trace(this, param, () -> ...)`。重试即重订阅，`Mono.deferContextual` 的体会重跑一遍，于是同一次工具调用建出第二个 `execute_tool` span。起止时刻更麻烦：`facts.markStarted/markEnded` 用的是 `compareAndSet(0L, ...)`，第二个 span 会套用第一次尝试的区间，时间轴上两个节点重叠。

现在不触发，因为全项目没有一处配 `ExecutionConfig.maxAttempts`，而 `applyRetry` 在 `maxAttempts == null || <= 1` 时直接原样返回。哪天要给工具配重试，先把这个 span 的重订阅处理掉再配。

模型侧的重试不归这个包管，但别当它不存在。有效配置由三段拼出来：

- `OpenAIChatModel.Builder.build()` 调 `ModelUtils.ensureDefaultExecutionConfig`，把 `ExecutionConfig.MODEL_DEFAULTS` 装进模型的 `defaultOptions`：5 分钟超时、3 次尝试、`retryOn` 取 `RETRYABLE_ERRORS`
- `ReActAgent.buildGenerateOptions()` 每次调用另建一份 `executionConfig`，只填 `maxAttempts(modelConfig.maxRetries())`，其余留空
- `OpenAIChatModel` 里是 `mergeOptions(options, configuredOptions)`：调用级是 primary，模型默认是 fallback，`mergeConfigs` 逐字段取非空

所以真正生效的是**调用级的尝试次数 + 模型默认的其余各项**：`agent.max-retries` 配 N，就是“N 次尝试，仅对 429 / 5xx / `TimeoutException` / `IOException` 重试”，既不是 `MODEL_DEFAULTS` 那个 3，也不是所有异常。

**要关只能改 `agent.max-retries`。**给 `OpenAIChatModel.defaultOptions` 配 `maxAttempts(1)` 是无效的：它是 fallback，被调用级那份原样盖掉，改了等于没改。还要留意这个字段名叫 retries、值却直接喂给 `maxAttempts`，语义是“含首次的总次数”，且 `ModelConfig` 校验必须大于 0，所以 1 就是“不重试”的地板值。

要紧的是 `retryWhen` 加在整条 `doStream0` 之上，位置在 `onModelCall` 之下，不管流已经吐过多少 chunk，出错就重订阅。断网、读超时、半程 5xx 都在可重试之列，于是下游收到“前半截 + 完整第二遍”——累加的不只是追踪 Middleware 的缓冲，还有框架的 `transformedText` 与 `ReasoningContext`、SSE 增量、落库正文。追踪如实反映了框架手里那份已被污染的状态，**改这个包等于把证据擦掉**。所以 `agent.max-retries` 保持 1，等实现“仅在首个 chunk 之前允许重试”再放开。

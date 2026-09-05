# 双引擎 Dashboard

## 改动边界

只修改管理端查询和首页，不改聊天执行链路、工具执行、确认续跑、消息/记忆写入，也不新增数据库字段或迁移。

- `ragent.engine.type=workflow`（或缺省）：装配原有 `DashboardServiceImpl`。
- `ragent.engine.type=agent`：装配 `AgentDashboardService`。
- `/admin/dashboard/overview`、`/performance`、`/trends` 路径不变，均要求管理员角色。
- `overview.engine` 与 `performance.engine` 是后端按部署配置返回的判别字段，首页不提供引擎切换按钮。
- 前后端需要一起更新；无需数据库迁移。运行中的后端需重启才能加载新的统计实现。

## 统计口径

| 指标 | 口径 |
| --- | --- |
| 新建会话 | 当前引擎会话表中，窗口内创建且未逻辑删除的会话 |
| 消息数 | 当前引擎消息表中，窗口内创建且未逻辑删除的消息 |
| 活跃用户 | 窗口内消息的去重用户数 |
| 活跃会话 | 窗口内消息按 `(conversation_id, user_id)` 去重；包含旧会话续聊 |
| 活跃会话消息均值 | 窗口内消息数 / 活跃会话数；没有活跃会话时显示缺失，不显示 0 |
| 已记录回复状态 | 窗口内创建的助手消息的当前状态，未知状态单列；不是请求成功率 |
| 工具调用 | 窗口内助手消息的 `kind=tool` 块数，区分 done / failed / interrupted / 其他状态 |
| 每条有轨迹回复的调用数 | 工具块数 / 有非空数组轨迹的助手回复数；空、缺失、非数组轨迹不作为零调用样本 |
| 工具分布 | 按工具内部名聚合，返回 Top 8，首页将剩余调用数列为“其他工具” |
| 人工确认 | 确认卡（不是卡内工具）数；批准率 = approved / (approved + denied)；分母为零显示缺失 |
| 压缩次数 | 窗口内上下文压缩事件数 |
| 上下文缩减比例 | `100 × (1 - sum(after) / sum(before))`；只纳入 before > 0 的事件，不是 Token 节省率 |
| 有效记忆 | 截至统计时间已创建且未失效的长期记忆存量，不受窗口起点限制 |
| 新增 / 失效记忆 | 分别按 create_time / invalid_at 落在窗口内统计 |

Agent 的回复、工具与确认趋势都按所属消息的 **create_time** 归组，状态取查询时的当前值。确认续跑可能更新历史桶；它们不是不可变事件流水。待确认数量仅覆盖所选窗口的回复，不代表全站积压。

没有引入精确耗时、P95、独立错误原因分类或直答质量评分。中断仍混合用户停止与执行异常；没有数据支撑时不生成质量结论。Workflow 增加 `sampleCount`，用于区分无追踪样本与真实的 0% 成功率。

## 查询与缓存

Agent 统计仅支持 24h / 7d / 30d，粒度为 hour / day。时间过滤为 `[start, end)`；趋势首尾桶只包含落在滚动窗口内的记录，不补读整小时或整日。无记录的计数桶补零。

聚合在 PostgreSQL 内完成，只返回计数、状态和工具名称，不返回聊天正文、工具参数/结果、记忆内容或用户标识。读模型使用只读、可重复读事务和 10 秒事务超时。

每实例按“窗口 + 粒度”最多缓存 6 个快照，30 秒过期；同一窗口的三个接口复用快照。刷新不强制绕过缓存，`updatedAt` 表示数据统计时间而不是浏览器刷新时间。查询失败不缓存为零。

只读不意味着没有数据库开销：总量统计仍需扫描现有表，JSONB 统计也消耗 CPU。此次不改索引；大数据量部署应先观察实际查询耗时，再单独评估索引或离线聚合。

## 验证

后端回归：

```sh
mvn -q -pl agent -am -Dtest=DashboardServiceImplTest,AgentDashboardServiceTest,AgentDashboardReaderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

`AgentDashboardReaderTest` 默认跳过真实数据库验证。显式提供 `-Dragent.dashboard.test.jdbc-url=jdbc:postgresql://localhost:5432/postgres?user=YOUR_USER` 后启用；测试只在独立连接中创建临时表，最后回滚，不修改持久业务表。

前端隔离预览：在 `frontend` 目录运行 `npm run dev -- --port 5175`，访问 `/tests/dashboard-preview.html`。该入口不在生产路由/构建入口中，全部使用本地测试数据，API adapter 禁止访问业务接口。

预览覆盖 Agent / Workflow、sample / empty / missing / error / trend-error，可切换时间窗口并检查桌面和移动端。它不是已部署后端的端到端联调。

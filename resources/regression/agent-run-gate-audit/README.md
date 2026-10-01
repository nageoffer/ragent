# AgentRunGate 回归检查

验证会话互斥、用户并发名额、跨线程释放和看门狗回收。默认先运行五个相关 Java 测试类，再启动临时本机 Redis 执行探针，不调用模型或工具。

需要 Java 17 JDK、Maven、`redis-server`，以及已缓存的 Maven 依赖。Redis 使用随机端口，只监听 `127.0.0.1`，不持久化；正常结束、失败或中断时会清理进程。

在仓库根目录运行：

```shell
bash resources/regression/agent-run-gate-audit/run.sh
```

复用已生成的测试报告和编译产物，跳过 Maven 测试：

```shell
bash resources/regression/agent-run-gate-audit/run.sh --skip-tests
```

`--skip-tests` 前需确认编译产物与当前源码一致，否则检查的是旧版本。

每次运行的日志和探针编译产物保存在本套件 `artifacts/run-*` 目录，已被 Git 忽略。执行失败时退出码非零，并输出对应日志路径。

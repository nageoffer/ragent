# Ticket 状态机与排队凭证机制详解

## 1. 文档目标

本文聚焦 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/ratelimit/FairDistributedRateLimiter.java` 内部类 `Ticket` 的设计与实现，回答以下问题：

- `Ticket` 在整个流式对话限流链路中的定位是什么
- `Ticket` 与一次 `/rag/v3/chat` 请求、一个 chat 任务之间是什么关系
- `Ticket` 如何与 Redis 队列、可过期信号量、轮询器、SSE 生命周期协作
- `Ticket` 的状态机为什么这样设计
- `Ticket` 如何解决并发竞争、资源释放、僵尸请求、重复回调等问题

本文不重复展开整个限流器的所有实现细节，而是以 `Ticket` 为中心，把上下游关键协作模块串起来。

---

## 2. 先给结论：Ticket 到底是什么

`Ticket` 不是业务层的 chat 任务对象，也不是 Redis 队列里的完整消息体。  
它的本质是：

- **一次排队请求在本机 JVM 中的状态代理对象**
- **一次 chat 请求进入分布式公平限流器后的“排队凭证 / 通行证”**
- **连接排队状态、资源 permit、超时、取消、清理逻辑的单请求协调器**

如果一句话定义：

> `Ticket` 负责回答“这次请求现在是否还在等待、是否已获得执行资格、是否已超时/取消、是否应释放资源、是否应触发真正的业务执行”。

它关注的是“什么时候允许执行”，而不是“执行什么业务”。

---

## 3. Ticket 在整体链路中的位置

以流式对话接口 `GET /rag/v3/chat` 为例，整体调用链如下：

1. `RAGChatController.chat(...)`
2. `RAGChatServiceImpl.streamChat(...)`
3. `ChatQueueLimiter.enqueue(...)`
4. `FairDistributedRateLimiter.acquire(...)`
5. `new Ticket(req)`
6. `Ticket` 在排队、轮询、取消、超时、抢占 permit 的竞争中推进状态
7. `ticket.grant(...)`
8. `req.onAcquired().run()`
9. `traceRunner.run(...)`
10. `chatPipeline.execute(ctx)`

关键入口代码：

```java
chatQueueLimiter.enqueue(question, actualConversationId, emitter,
        () -> traceRunner.run(question, actualConversationId, taskId, callback, traceAware -> {
            StreamChatContext ctx = StreamChatContext.builder()
                    .question(question)
                    .conversationId(actualConversationId)
                    .taskId(taskId)
                    .deepThinking(Boolean.TRUE.equals(deepThinking))
                    .userId(UserContext.getUserId())
                    .callback(traceAware)
                    .build();
            chatPipeline.execute(ctx);
        }));
```

这里真正的业务执行逻辑是外层 `Runnable`。  
`Ticket` 并不持有完整的 `StreamChatContext`，而是通过 `AcquireRequest.onAcquired` 间接持有“拿到执行资格后要执行的业务动作”。

---

## 4. Ticket 与 chat 请求 / chat 任务的关系

### 4.1 一次 chat 请求，对应一个 Ticket

在 `FairDistributedRateLimiter.acquire(...)` 中，每次接入一个 `AcquireRequest`，都会创建一个新的 `Ticket`：

```java
public void acquire(AcquireRequest req) {
    Ticket ticket = new Ticket(req);
    ...
}
```

因此对于一次 `/rag/v3/chat` 请求：

- 会创建一个 `AcquireRequest`
- 会创建一个 `Ticket`
- 该 `Ticket` 最终只会走向一个终态：
  - `GRANTED`
  - `TIMED_OUT`
  - `CANCELLED`

### 4.2 Ticket 不是 chat 任务本身

需要明确区分两件事：

- **chat 任务**
  - 真正执行 `traceRunner.run(...) -> chatPipeline.execute(ctx)` 的业务流程
- **Ticket**
  - 限流排队层的单请求状态机
  - 决定该 chat 任务是否能开始、何时开始、是否在开始前被取消/超时

所以二者的关系是：

- 一个 `Ticket` 通常 1:1 对应一个 chat 请求
- `Ticket` 控制业务任务的放行
- `Ticket` 自己不是业务任务对象

更准确地说：

> `Ticket` 是 chat 任务进入限流排队系统后的“控制平面对象”。

---

## 5. AcquireRequest：Ticket 的外部输入模型

`Ticket` 的构造参数是 `AcquireRequest`，它定义在 `FairDistributedRateLimiter` 内部：

```java
@Builder
public record AcquireRequest(long maxWaitMillis,
                             Runnable onAcquired,
                             Runnable onTimeout,
                             Executor onAcquiredExecutor,
                             Consumer<Runnable> cancelBinder) {
```

各字段语义如下：

- `maxWaitMillis`
  - 最大等待时长
- `onAcquired`
  - 拿到 permit 后真正执行业务的入口
- `onTimeout`
  - 超时拒绝时的回调
- `onAcquiredExecutor`
  - 真正执行 `onAcquired` 的线程池
- `cancelBinder`
  - 一个取消动作绑定器，用来把 `ticket.cancel()` 绑定到外部生命周期事件

对 chat 场景来说，`cancelBinder` 会绑定到 `SseEmitter` 生命周期：

```java
.cancelBinder(cancel -> {
    emitter.onCompletion(cancel);
    emitter.onTimeout(cancel);
    emitter.onError(e -> cancel.run());
})
```

因此，`Ticket` 并不是孤立对象，而是通过 `AcquireRequest` 和外部业务环境发生关联。

---

## 6. Ticket 的字段设计

`Ticket` 定义如下：

```java
private final class Ticket {
    final String requestId = IdUtil.getSnowflakeNextIdStr();
    final long deadline;
    final AcquireRequest req;
    final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
    final AtomicReference<String> permitRef = new AtomicReference<>();
    volatile ScheduledFuture<?> future;
```

### 6.1 `requestId`

- 当前排队请求在限流器里的唯一标识
- 用途：
  - Redis ZSET 队列成员值
  - entry marker key 后缀
  - notifier 注册键
  - 日志排查标识

### 6.2 `deadline`

- 当前请求的绝对超时时刻
- 计算方式：

```java
this.deadline = System.currentTimeMillis() + req.maxWaitMillis();
```

- 作用：
  - poller 检测排队是否超时
  - 重写 entry marker TTL 时计算剩余生存时间

### 6.3 `req`

- 外部传入的 `AcquireRequest`
- 提供 `onAcquired / onTimeout / onAcquiredExecutor / cancelBinder`
- `Ticket` 的所有行为都围绕这份请求描述展开

### 6.4 `state`

- `AtomicReference<State>`
- 当前请求状态机的单一事实来源
- 负责“终态互斥”

### 6.5 `permitRef`

- 当前 Ticket 持有的 permit id
- 之所以不是 boolean，是因为 `RPermitExpirableSemaphore` 释放时需要具体 `permitId`
- 使用 `AtomicReference<String>`，保证：
  - permit 最多释放一次
  - 多线程下不会双重释放

### 6.6 `future`

- 当前 ticket 对应的本地定时轮询任务句柄
- 作用：
  - 在请求终态后，及时取消后续轮询

---

## 7. State 状态机设计

`Ticket` 使用一个非常简洁但非常关键的状态机：

```java
private enum State {PENDING, GRANTED, TIMED_OUT, CANCELLED}
```

状态转移图：

```text
PENDING -> GRANTED
PENDING -> TIMED_OUT
PENDING -> CANCELLED
```

### 7.1 为什么只有一个中间态

作者刻意把状态机收敛到一个唯一中间态 `PENDING`，其他全部为终态。  
这样带来的好处是：

- 终态竞争逻辑极简
- 所有终态通过同一个 CAS 协调点竞争
- 业务回调天然最多执行一次

### 7.2 单 CAS 协调点

所有关键路径都围绕：

- `state.compareAndSet(PENDING, XXX)`

例如：

- `cancel()`
- `timeout()`
- `grant()`

谁先抢到从 `PENDING` 到终态的 CAS，谁就赢。  
其余路径即便晚到，也只能做幂等收尾，不能再触发业务。

这就是注释里“单 CAS 协调点”的本质含义。

---

## 8. Ticket 的方法详解

### 8.1 `isPending()`

```java
boolean isPending() {
    return state.get() == State.PENDING;
}
```

作用：

- 快速判断当前请求是否仍有资格继续竞争
- 是所有后续逻辑的前置条件

使用场景：

- `tryAcquireIfReady(ticket)`
- 重入队后的状态回查
- permit 拿到后的再次确认
- poller 轮询前检查

---

### 8.2 `cancel()`

```java
void cancel() {
    state.compareAndSet(State.PENDING, State.CANCELLED);
    cleanup();
}
```

#### 触发来源

- 不是限流器内部主动调用，而是通过 `cancelBinder` 绑定到业务外部生命周期事件
- 在 chat 场景中，具体触发源是：
  - `emitter.onCompletion(...)`
  - `emitter.onTimeout(...)`
  - `emitter.onError(...)`

#### 语义

- 尝试把当前请求从 `PENDING` 改成 `CANCELLED`
- 无论 CAS 是否成功，都执行 `cleanup()`

#### 为什么 CAS 失败仍然 cleanup

因为可能出现：

- 另一个线程已经把它推进到终态
- 但本地 notifier/future/队列项仍需清理

因此 `cleanup()` 被设计成和状态机解耦、可重复执行。

#### 特别重要的点

`cancel()` 的注释强调：

> `GRANTED` 状态下不释放 permit —— permit 已经被 `grant()` 的包装 `try/finally` 接管。

也就是说：

- `cancel()` 可以在任何时刻被触发
- 但一旦请求已经进入执行态，permit 生命周期就不再归取消路径管理

---

### 8.3 `timeout()`

```java
void timeout() {
    if (!state.compareAndSet(State.PENDING, State.TIMED_OUT)) {
        return;
    }
    cleanup();
    submitSafely(req.onTimeout(), "onTimeout");
}
```

#### 触发来源

- `scheduleQueuePoll(...)` 中的定时轮询发现：

```java
if (System.currentTimeMillis() > ticket.deadline) {
    ticket.timeout();
    return;
}
```

#### 语义

- 抢占 `PENDING -> TIMED_OUT`
- 成功后执行：
  - 资源清理
  - 超时拒绝回调

#### 为什么 `onTimeout` 不直接同步执行

它通过：

```java
submitSafely(req.onTimeout(), "onTimeout");
```

提交到 `onAcquiredExecutor`，这意味着：

- timeout 回调与正常 onAcquired 保持统一线程模型
- 避免定时线程直接承担业务输出职责

---

### 8.4 `grant(String permitId)`

这是 `Ticket` 最核心的方法，也是整个状态机最复杂的部分。

```java
boolean grant(String permitId) {
    permitRef.set(permitId);
    if (!state.compareAndSet(State.PENDING, State.GRANTED)) {
        if (permitRef.compareAndSet(permitId, null)) {
            releasePermitQuietly(permitId);
            publishQueueNotify();
        }
        return false;
    }
    unregisterFromNotifier();
    cancelFutureQuietly();
    Runnable wrapped = () -> {
        try {
            req.onAcquired().run();
        } finally {
            releaseHeldPermit();
        }
    };
    try {
        req.onAcquiredExecutor().execute(wrapped);
        return true;
    } catch (RejectedExecutionException ex) {
        ...
    }
}
```

#### 阶段一：先登记 permit，再 CAS 改状态

```java
permitRef.set(permitId);
if (!state.compareAndSet(State.PENDING, State.GRANTED)) { ... }
```

这是一个非常关键的并发细节。

为什么不是先 CAS 再设 permit？

因为如果顺序反过来，会出现这样的竞争窗口：

1. 线程 A 先把状态改成 `GRANTED`
2. 线程 B 同时触发 `cancel/timeout`
3. 线程 B 看到 ticket 已终态，但还没看到 permitRef
4. permit 失去正确归属，可能泄漏

现在改成“先写 `permitRef`，再 CAS”后：

- 并发 `cancel/timeout` 至少能看到 permit 存在
- 即使 CAS 失败，也能走补偿释放路径

#### 阶段二：如果 CAS 失败，回滚 permit

```java
if (permitRef.compareAndSet(permitId, null)) {
    releasePermitQuietly(permitId);
    publishQueueNotify();
}
```

说明：

- 这次 permit 已经不属于当前 ticket
- 必须释放 permit，避免资源泄漏
- 同时发通知，让其他等待者尽快推进

#### 阶段三：进入执行态后，注销 notifier 和定时轮询

```java
unregisterFromNotifier();
cancelFutureQuietly();
```

因为一旦 `GRANTED`：

- 这个 ticket 不再是“等待者”
- 不需要再接收通知
- 不需要再被周期性 poll

#### 阶段四：用包装任务接管 permit 生命周期

```java
Runnable wrapped = () -> {
    try {
        req.onAcquired().run();
    } finally {
        releaseHeldPermit();
    }
};
```

这一步定义了资源所有权切换：

- `GRANTED` 之前，permit 归状态机/cleanup 管
- `GRANTED` 之后，permit 归业务执行线程 `finally` 管

这是整个设计的核心之一。

#### 阶段五：提交业务执行

```java
req.onAcquiredExecutor().execute(wrapped);
```

对 chat 场景来说，`onAcquiredExecutor` 就是 `chatEntryExecutor`。  
也就是说：

- `Ticket` 自己不跑聊天业务
- 它只把业务提交给真正的业务线程池

#### 阶段六：处理线程池拒绝

如果线程池拒绝了 `wrapped`：

- 说明 permit 已拿到，但业务没真正开始执行
- 这时必须：
  - 释放 permit
  - cleanup
  - 降级执行 `onTimeout(fallback)`

这是一个非常细的异常补偿分支，防止“资源已占住，但业务没跑起来”的中间态泄漏。

---

### 8.5 `releaseHeldPermit()`

```java
void releaseHeldPermit() {
    String pid = permitRef.getAndSet(null);
    if (pid != null) {
        releasePermitQuietly(pid);
        publishQueueNotify();
    }
}
```

作用：

- 释放当前 ticket 持有的 permit
- 并通知等待者资源已经腾出

为什么使用 `getAndSet(null)`：

- 确保 permit 最多只释放一次
- 多线程并发调用下天然幂等

它通常在两个地方触发：

- `grant()` 包装的业务 `finally`
- 补偿路径中的显式释放

---

### 8.6 `cleanup()`

`cleanup()` 是整个 `Ticket` 的统一收尾函数。

```java
void cleanup() {
    boolean removed = false;
    try {
        removed = redissonClient.getScoredSortedSet(queueKey, StringCodec.INSTANCE).remove(requestId);
    } catch (Exception ex) {
        ...
    }
    deleteEntryMarker(requestId);

    boolean releasedPermit = false;
    if (state.get() != State.GRANTED) {
        String permitId = permitRef.getAndSet(null);
        if (permitId != null) {
            releasePermitQuietly(permitId);
            releasedPermit = true;
        }
    }
    if (removed || releasedPermit) {
        publishQueueNotify();
    }
    unregisterFromNotifier();
    cancelFutureQuietly();
}
```

#### 它做了什么

1. 从 Redis ZSET 公平队列移除自己
2. 删除 entry marker
3. 在非 `GRANTED` 状态下尝试释放 permit
4. 如果队列/资源发生变化，发通知
5. 注销 notifier
6. 取消本地 future

#### 为什么 `GRANTED` 状态下不释放 permit

因为这时 permit 已经交给业务执行线程的 `finally` 托管。  
如果 cleanup 也释放：

- 业务还在执行
- permit 却提前回到 semaphore
- 后续请求可能错误地拿到同一并发槽位

这会直接破坏全局并发上限。

#### 为什么 `cleanup()` 必须幂等

因为它可能被多条路径重复触发：

- `cancel()`
- `timeout()`
- `grant()` 的失败补偿
- 并发竞态失败后的兜底

因此它内部大量使用：

- `remove(...)`
- `getAndSet(null)`
- “尽力而为 + no-op 安全”的方式

这是工程上非常成熟的资源回收设计。

---

### 8.7 `unregisterFromNotifier()` 与 `cancelFutureQuietly()`

```java
void unregisterFromNotifier() {
    pollNotifier.unregister(requestId);
}

void cancelFutureQuietly() {
    ScheduledFuture<?> f = future;
    if (f != null && !f.isCancelled()) {
        f.cancel(false);
    }
}
```

作用：

- 结束本地等待者身份
- 停止后续本地轮询推进

这两步和 Redis 资源清理一起，构成“本地 + 分布式”双层收尾。

---

### 8.8 `submitSafely(...)`

```java
private void submitSafely(Runnable r, String label) {
    try {
        req.onAcquiredExecutor().execute(r);
    } catch (Exception ex) {
        log.warn("[{}] {} 提交失败，回调被丢弃", name, label, ex);
    }
}
```

作用：

- 把 `onTimeout()` 这类回调也统一提交到业务执行线程池
- 如果提交失败，只记录日志，不再影响主状态收敛

这说明作者对“回调执行失败不能反过来破坏状态机收尾”这一点是有明确边界意识的。

---

## 9. Ticket 与哪些模块合作

### 9.1 与 `ChatQueueLimiter` 合作

`ChatQueueLimiter` 负责把 chat 场景的业务特征映射成通用限流请求：

- `onAcquired`
  - 真正的 traceRunner/chatPipeline 入口
- `onTimeout`
  - reject 业务回路
- `onAcquiredExecutor`
  - `chatEntryExecutor`
- `cancelBinder`
  - 绑定到 `SseEmitter` 生命周期

`Ticket` 本身并不知道什么是 SSE、conversation、question，它只消费这些抽象回调。

### 9.2 与 `PollNotifier` 合作

`PollNotifier` 是本地通知合并器：

- `Ticket` 注册自己的 poller
- permit 释放或队列结构变化时，会 `publishQueueNotify()`
- 当前节点收到 Redis topic 通知后，再由 `pollNotifier.fire()` 批量唤醒本地等待 ticket

### 9.3 与 Redis ZSET 队列合作

队列承担公平排序：

- `requestId` 入队
- `cleanup()` 移队
- `tryAcquireIfReady()` 中可能 claim/remove/requeue

### 9.4 与 entry marker 合作

entry marker 是 Redis 中的“请求存活哨兵”：

- 入队前写入
- cleanup 时删除
- Lua 脚本据此识别僵尸请求

### 9.5 与 `RPermitExpirableSemaphore` 合作

它负责真实的全局并发槽位控制：

- `tryAcquirePermit()`
- `releasePermitQuietly(permitId)`

Ticket 负责保证 permit 生命周期与状态机一致，不泄漏、不双重释放。

### 9.6 与 Lua 脚本合作

`claimIfReady(...)` 会调用 `queue_claim_atomic.lua`，由 Redis 原子完成：

- 是否轮到当前 `requestId`
- 是否需要清理僵尸队首
- 是否允许 claim

Ticket 不自己判断“我是不是队首”，而是借助 Lua 保证跨节点一致性。

---

## 10. Ticket 的完整工作流程

下面按一次正常 chat 请求的生命周期来串联 `Ticket`。

### 10.1 创建阶段

1. `ChatQueueLimiter.enqueue(...)`
2. 构造 `AcquireRequest`
3. `FairDistributedRateLimiter.acquire(req)`
4. `new Ticket(req)`

此时：

- `state = PENDING`
- `deadline = now + maxWaitMillis`
- `requestId` 已生成

### 10.2 接入阶段

1. 如果存在 `cancelBinder`，执行：

```java
req.cancelBinder().accept(ticket::cancel);
```

2. 写 entry marker
3. `requestId` 入 Redis ZSET 队列
4. 立即尝试一次 `tryAcquireIfReady(ticket)`
5. 若失败，注册本地 poller 与 future

### 10.3 等待阶段

等待中的 ticket 会通过两种机制被推进：

- 本地 `scheduler` 周期性 poll
- Redis topic 通知唤醒后，本地 `pollNotifier.fire()` 批量触发 poller

每次推进都会尝试：

- `tryAcquireIfReady(ticket)`

### 10.4 成功放行阶段

如果满足：

- ticket 仍为 `PENDING`
- permit 看起来有空位
- Lua claim 成功
- 真正 permit 获取成功

则调用：

- `ticket.grant(permitId)`

然后：

- 状态转为 `GRANTED`
- permit 生命周期交给包装业务线程
- `req.onAcquired().run()` 被提交到 `chatEntryExecutor`

### 10.5 超时阶段

如果 poller 发现：

- `System.currentTimeMillis() > deadline`

则：

- `ticket.timeout()`
- 状态变为 `TIMED_OUT`
- cleanup
- 执行 `onTimeout`

### 10.6 取消阶段

如果用户关闭 SSE、超时或网络异常，则：

- `ticket.cancel()`
- 尝试转为 `CANCELLED`
- cleanup

### 10.7 结束阶段

无论走哪条路径，最终都会完成：

- 队列项移除
- entry marker 清理
- permit 释放或移交
- notifier 注销
- future 取消

---

## 11. `tryAcquireIfReady()`：Ticket 被推进的核心点

虽然本文聚焦 `Ticket`，但必须理解它是如何被推进的。

`tryAcquireIfReady(ticket)` 的核心逻辑是：

1. `ticket.isPending()` 快速校验
2. 检查 `availablePermits()`
3. `claimIfReady(ticket.requestId, avail)`：Lua 原子 claim 队列资格
4. `tryAcquirePermit()`：真正获取 semaphore permit
5. 根据结果：
   - 失败则补偿重入队
   - 中途终态则释放 permit
   - 成功则 `ticket.grant(permitId)`

这意味着：

> `Ticket` 自己不主动推进自己，它是被 poll/notify 驱动，并通过 `tryAcquireIfReady()` 被动推进的。

---

## 12. 为什么 Ticket 要这样设计

### 12.1 解决“业务回调最多执行一次”

通过单 CAS 终态机：

- `grant / timeout / cancel`
- 只能有一条路径真正抢到终态

从而保证：

- `onAcquired` 不会和 `onTimeout` 同时发生
- 不会出现“既执行了 chat，又返回超时拒绝”

### 12.2 解决 permit 泄漏与双重释放

通过：

- `permitRef`
- 先 set 后 CAS
- `getAndSet(null)`
- `GRANTED` 后 permit 所有权交给业务线程

确保：

- permit 不会丢
- permit 不会重复释放

### 12.3 解决僵尸请求问题

通过：

- entry marker
- cleanup 显式删 marker
- marker TTL 兜底
- Lua claim 时清理僵尸队首

确保：

- 进程异常退出、取消未及时收敛时，不会让死请求永久占队首

### 12.4 解决本地等待者资源泄漏

通过：

- `unregisterFromNotifier()`
- `cancelFutureQuietly()`

确保：

- 终态后不再被唤醒
- 不再继续轮询

### 12.5 解决业务执行线程池拒绝

即使拿到 permit 后，业务线程池也可能拒绝任务。  
`grant()` 中专门做了 fallback：

- 释放 permit
- cleanup
- 走 reject/onTimeout 回路

这避免了“拿到全局资源但本地根本跑不起来”的中间态。

---

## 13. 从设计思想上评价 Ticket

### 13.1 控制面与执行面分离

`Ticket` 只管理：

- 排队
- 放行
- 取消
- 超时
- 资源所有权

真正的业务执行交给：

- `req.onAcquired()`

这是一种很干净的分层。

### 13.2 状态机与 cleanup 解耦

状态机只做“命运裁决”，cleanup 只做“现场收尾”。  
这样每条路径都可以：

- 安全抢终态
- 失败后继续做幂等清理

这非常适合异步并发场景。

### 13.3 先保证正确性，再优化推进效率

`Ticket` 首先保证：

- 不双回调
- 不双释放
- 不泄漏

在此基础上再通过：

- `publishQueueNotify()`
- `PollNotifier`
- 轮询兜底

提升推进速度。

### 13.4 对异常和竞态非常敏感

整个 `Ticket` 设计中最突出的特点，就是对 race condition 的处理非常细：

- claim 成功但 permit 失败
- permit 拿到后 ticket 已终态
- grant 与 cancel 并发
- cleanup 重复触发
- 线程池拒绝

这说明它不是“演示性质代码”，而是按真实线上并发问题来设计的。

---

## 14. 适合面试时怎么概括 Ticket

可以这样回答：

> 在我的流式对话限流方案里，`Ticket` 是一次 chat 请求进入分布式公平排队系统后的单请求状态代理。它负责维护请求在本机 JVM 中的等待态、放行态、超时态和取消态，并通过一个单 CAS 状态机协调 `grant / timeout / cancel` 的并发竞争，保证业务回调最多执行一次。  
> 同时它还负责 permit 生命周期管理、Redis 队列项和 entry marker 的清理、本地 poller 和 notifier 的回收。也就是说，`Ticket` 不做具体 chat 业务，而是解决“这次 chat 什么时候能执行、执行前后资源如何正确收敛”的问题。

---

## 15. 总结

`Ticket` 是 `FairDistributedRateLimiter` 中最关键的单请求控制单元。  
它的价值不在字段本身，而在于它把以下能力统一收敛到了一个小而完整的状态代理对象中：

- 单请求状态机
- permit 所有权管理
- timeout/cancel/grant 终态竞争
- Redis 队列与 entry marker 清理
- 本地轮询器与通知器回收
- 业务执行回调的单次触发保证

如果没有 `Ticket`，整个分布式公平限流器将很难同时满足：

- 公平性
- 并发正确性
- 资源不泄漏
- 异常可恢复
- 业务回调单次执行

因此，从架构角度看：

> `FairDistributedRateLimiter` 解决的是“全局如何公平限流”，而 `Ticket` 解决的是“单个请求如何安全地穿过这套限流系统”。  

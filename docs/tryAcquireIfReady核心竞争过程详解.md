# tryAcquireIfReady 核心竞争过程详解

## 1. 文档目标

本文专门讲解 `FairDistributedRateLimiter` 中最核心的方法：

- [tryAcquireIfReady](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/ratelimit/FairDistributedRateLimiter.java#L313-L348)

它承担的是分布式公平排队限流器中最关键的职责：

- 判断当前请求是否已经具备进入执行态的资格
- 串联 Redis 公平队列、Lua 原子 claim、可过期 permit 获取、Ticket 状态机推进与失败补偿
- 在多线程、多节点、长连接取消、超时并发竞争下保证公平性与正确性

本文会围绕以下问题展开：

- `tryAcquireIfReady` 在整体链路中位于哪里
- 它的输入、输出和语义是什么
- 它为什么必须分成“claim 队列资格”与“获取 permit”两步
- 它如何处理各种 race condition
- 它如何与 `Ticket`、Lua、Redis ZSET、PermitExpirableSemaphore、PollNotifier 协作

---

## 2. 方法定位

方法定义如下：

```java
private boolean tryAcquireIfReady(Ticket ticket) {
    if (!ticket.isPending()) {
        return false;
    }
    int avail = availablePermits();
    if (avail <= 0) {
        return false;
    }
    long claimedScore = claimIfReady(ticket.requestId, avail);
    if (claimedScore < 0L) {
        return false;
    }
    String permitId = tryAcquirePermit();
    if (permitId == null) {
        ...
        return false;
    }
    if (!ticket.isPending()) {
        ...
        return false;
    }
    publishQueueNotify();
    return ticket.grant(permitId);
}
```

从职责上看，它不是：

- 入队方法
- 超时处理方法
- permit 释放方法
- 业务执行方法

它是：

> “等待中的 Ticket 被唤醒后，尝试从 `PENDING` 推进到 `GRANTED` 的核心竞争入口”。

---

## 3. 它在整体链路中的位置

对一次 `/rag/v3/chat` 请求来说，整体链路如下：

1. `RAGChatController.chat(...)`
2. `RAGChatServiceImpl.streamChat(...)`
3. `ChatQueueLimiter.enqueue(...)`
4. `FairDistributedRateLimiter.acquire(req)`
5. `new Ticket(req)`
6. `requestId` 写入 Redis 队列并写入 entry marker
7. 立即尝试一次 `tryAcquireIfReady(ticket)`
8. 如果失败，进入 `scheduleQueuePoll(ticket)`
9. 由：
   - 本地定时轮询
   - Redis Topic 通知 + PollNotifier 唤醒
   反复触发 `tryAcquireIfReady(ticket)`
10. 一旦成功，`ticket.grant(permitId)`
11. `req.onAcquired()` 提交到 `chatEntryExecutor`
12. 真正开始 `traceRunner.run(...) -> chatPipeline.execute(ctx)`

所以：

- `acquire(...)` 负责入队
- `scheduleQueuePoll(...)` 负责反复尝试
- `tryAcquireIfReady(...)` 负责“是否现在轮到你”

---

## 4. 输入与输出语义

### 4.1 输入：`Ticket ticket`

`Ticket` 是当前请求在本机 JVM 中的状态代理对象，内部至少包含：

- `requestId`
- `deadline`
- `req`
- `state`
- `permitRef`

它并不是 chat 任务本身，而是这次请求在限流系统中的排队凭证。

### 4.2 输出：`boolean`

返回值语义很简单：

- `true`
  - 当前调用成功把 Ticket 推进到了执行路径
  - 典型结果是 `ticket.grant(permitId)` 成功
- `false`
  - 当前这次尝试没有把 Ticket 推到执行态
  - 可能原因包括：
    - Ticket 已终态
    - 没有空闲 permit
    - 还没轮到当前请求
    - claim 成功但 permit 被别人先抢走
    - permit 已拿到，但请求在窗口期被取消/超时

注意：

- `false` 不等于失败结束
- 更多时候它只是“这轮没抢到，下次再试”

---

## 5. 设计思想总览

在理解代码前，先理解作者为什么这样设计。

`tryAcquireIfReady()` 想解决的是一个多节点并发系统中的典型难题：

- 多个节点共享一套 Redis 公平队列
- 多个节点同时看到“看起来有空余 permit”
- 多个等待请求都想推进到执行态
- 用户可能在排队期间取消，或者超时
- 业务线程池可能在最后一步拒绝任务

如果没有一套严密的推进流程，就会出现：

- 不公平：后来的请求插队
- 泄漏：permit 永远不还
- 双重执行：同一个请求被执行两次
- 僵尸占位：取消或崩溃请求长期卡在队首

所以作者把整个推进过程拆成了三层：

1. **快速本地判断**
   - Ticket 是否还活着
   - permit 看起来是否还有空位
2. **Redis 层原子 claim**
   - 我是不是已经位于允许的公平窗口内
   - 队首前面有没有僵尸需要清理
3. **本地资源获取与状态推进**
   - 真的拿 permit
   - 处理中间竞态
   - 进入 `GRANTED`

这是整个方法的核心思想。

---

## 6. 代码逐段详解

### 6.1 第一步：当前 Ticket 还在等待态吗

```java
if (!ticket.isPending()) {
    return false;
}
```

#### 作用

第一步先做快速状态检查，避免无意义竞争。

#### 为什么这一步必须放最前面

`tryAcquireIfReady()` 可能被多次触发：

- `acquire(...)` 里入队后立即尝试一次
- `scheduleQueuePoll(...)` 定时轮询
- Redis Topic 通知到来后，`PollNotifier.fire()` 批量唤醒

而在这些触发之间，请求状态随时可能改变：

- 用户关闭 SSE 触发 `ticket.cancel()`
- 排队超时触发 `ticket.timeout()`
- 别的线程已经推进到 `GRANTED`

所以第一句必须先确认：

> 当前这张 Ticket 还不是终态，否则这轮推进没有任何意义。

#### 这是一个什么风格的检查

这是典型的：

- fail-fast
- 快速短路
- 避免无效 Redis 往返

---

### 6.2 第二步：看起来还有 permit 吗

```java
int avail = availablePermits();
if (avail <= 0) {
    return false;
}
```

`availablePermits()` 内部是：

```java
return redissonClient.getPermitExpirableSemaphore(semaphoreKey).availablePermits();
```

#### 作用

做一次“轻量的前置资源判断”。

如果：

- 当前全局并发槽位看起来已经为 0

那么根本没必要继续做 Lua claim，直接返回即可。

#### 为什么说这是“看起来还有 permit”

因为这是一个分布式场景，`avail > 0` 不是最终事实，只是当前读取时刻的快照。

举例：

1. 当前节点读到 `avail = 1`
2. 另一个节点几乎同时也读到 `avail = 1`
3. 双方都继续往后推进
4. 最终只有一方真的能拿到 permit

所以这一步只是：

- 优化性能
- 减少无意义的 Lua 调用

不是最终一致性保证。

#### 为什么还要把 `avail` 传给 Lua

后面 `claimIfReady(ticket.requestId, avail)` 会把 `avail` 作为：

- 当前最多允许推进的 rank 窗口大小

也就是说：

- 如果 permit 只有 `2`
- 那么只有当前队列里“活队头前两位”有资格 claim

这一步为后面公平窗口判定提供了输入。

---

### 6.3 第三步：Redis 原子 claim 队列资格

```java
long claimedScore = claimIfReady(ticket.requestId, avail);
if (claimedScore < 0L) {
    return false;
}
```

这是整个方法的第一处核心。

#### `claimIfReady(...)` 是什么

该方法内部会调用 Lua 脚本 [queue_claim_atomic.lua](file:///e:/java/workspace/ragent/bootstrap/src/main/resources/lua/queue_claim_atomic.lua)：

```java
List<Object> result = script.eval(
        RScript.Mode.READ_WRITE,
        claimLua,
        RScript.ReturnType.LIST,
        List.of(queueKey),
        requestId,
        String.valueOf(availablePermits),
        entryKeyPrefix
);
```

#### Lua 脚本做了什么

脚本核心逻辑如下：

```lua
local headEntries = redis.call('ZRANGE', queueKey, 0, maxRank + slack - 1)

local liveRank = -1
local liveCount = 0
for i = 1, #headEntries do
    local member = headEntries[i]
    if redis.call('EXISTS', entryPrefix .. member) == 1 then
        if member == requestId then
            liveRank = liveCount
        end
        liveCount = liveCount + 1
    else
        redis.call('ZREM', queueKey, member)
    end
end

if liveRank < 0 or liveRank >= maxRank then return {0} end

local score = redis.call('ZSCORE', queueKey, requestId)
redis.call('ZREM', queueKey, requestId)
redis.call('DEL', entryPrefix .. requestId)

return {1, score}
```

#### Lua 的原子职责

它一次性原子完成这些事：

1. 检查当前 `requestId` 是否位于允许的“活队头窗口”里
2. 检查窗口前面是否有僵尸请求
3. 如果有僵尸请求，直接从 ZSET 清掉
4. 如果当前请求符合推进条件，则：
   - 取出其原始 `score`
   - 从 ZSET 删除当前请求
   - 删除其 entry marker
   - 返回 `{1, score}`
5. 否则返回 `{0}`

#### 为什么返回 `claimedScore`

成功 claim 后返回原始 `score`，是为了后面可能需要：

- **按原位次重入队**

否则一旦后续 permit 获取失败，就会丢失公平顺序。

#### 为什么 `< 0` 直接返回 false

说明 Lua 判断结果是：

- 还没轮到当前请求
- 或它已经不在有效队列中
- 或窗口内有更早的活请求

此时当前这轮推进结束，继续等待即可。

---

### 6.4 第四步：claim 成功后，尝试真正获取 permit

```java
String permitId = tryAcquirePermit();
```

内部实现：

```java
return sem.tryAcquire(0, leaseSecondsSupplier.getAsInt(), TimeUnit.SECONDS);
```

#### 为什么 claim 成功还要再拿一次 permit

因为：

- `claim` 是“你现在有资格被推进”
- `permit` 是“你现在真的拿到了全局并发资源”

这两者不是同一件事。

在分布式场景下，即使：

- 你读到 `avail > 0`
- Lua 也允许你 claim

仍然可能出现：

- 其他节点在你 `tryAcquirePermit()` 之前把 permit 抢走了

所以这一步必须存在。

#### 为什么用 `RPermitExpirableSemaphore`

因为它返回的不是简单 true/false，而是 `permitId`：

- permit 可以按 id 精确释放
- permit 自带租期
- JVM 异常退出后 permit 也可通过 lease 自然回收

这比简单信号量更适合长连接和分布式场景。

---

### 6.5 第五步：claim 成功，但 permit 获取失败的补偿

```java
if (permitId == null) {
    setEntryMarker(ticket.requestId, Math.max(1, ticket.deadline - System.currentTimeMillis()));
    RScoredSortedSet<String> queue = redissonClient.getScoredSortedSet(queueKey, StringCodec.INSTANCE);
    queue.add(claimedScore, ticket.requestId);
    publishQueueNotify();
    if (!ticket.isPending()) {
        queue.remove(ticket.requestId);
        deleteEntryMarker(ticket.requestId);
    }
    return false;
}
```

这是整个方法最复杂、也最体现作者功底的部分之一。

#### 为什么会出现这种情况

典型时序：

1. 当前 ticket 通过 Lua claim 成功，已从队列里被移除
2. 但在调用 `tryAcquirePermit()` 时
3. permit 已经被其他节点更早拿走
4. 结果本地拿到 `permitId == null`

此时 ticket 处于一种危险中间态：

- 它已经不在队列里了
- 但又没有真正开始执行

如果什么都不做，这个请求就“丢了”。

#### 为什么要重新写 entry marker

在 claim 成功时，Lua 已经删除了：

- ZSET 中的 `requestId`
- 对应的 entry marker

如果现在要把请求重新放回队列，就必须重新设置：

- entry marker

否则后续 Lua 再扫到它时，会把它误判为僵尸请求清掉。

所以：

```java
setEntryMarker(ticket.requestId, remainingMillis)
```

是在恢复这次请求的“活性证明”。

#### 为什么要按原 `claimedScore` 重入队

```java
queue.add(claimedScore, ticket.requestId);
```

这是为了保持公平性。

如果 claim 成功但 permit 获取失败后随便重新排队：

- 当前请求会失去原有位次
- 公平队列被破坏

因此必须用原 score 回队，保留原本排队顺序。

#### 为什么重入队后要发通知

```java
publishQueueNotify();
```

因为队列结构发生了变化：

- 当前请求重新回到等待态
- 其他节点/等待者可能也需要重新判断推进机会

这里发通知是“加速收敛”，不是 correctness 必需条件，但能减少等待时间。

#### 为什么重入队后还要再次检查 `ticket.isPending()`

```java
if (!ticket.isPending()) {
    queue.remove(ticket.requestId);
    deleteEntryMarker(ticket.requestId);
}
```

这是为了处理一个非常关键的竞态窗口：

1. 当前线程刚把请求重新放回队列
2. 在这之后，另一个线程可能已经执行了：
   - `ticket.cancel()`
   - `ticket.timeout()`
3. 这时 ticket 已经终态
4. 如果不回查状态，这条刚放回去的队列项就会变成“僵尸占位”

所以这里必须做：

- **add 之后回查 state**

如果已经不是 `PENDING`：

- 立即把自己刚加进去的队列项和 marker 再删掉

这是避免“永久占队头窗口僵尸条目”的关键。

#### 为什么最终返回 false

因为这次推进没有成功把 ticket 变成执行态。

但它也不是彻底失败，而是：

- 进行了公平补偿
- 请求重新进入等待

---

### 6.6 第六步：permit 拿到了，但 ticket 可能在窗口期已终态

```java
if (!ticket.isPending()) {
    releasePermitQuietly(permitId);
    publishQueueNotify();
    return false;
}
```

这是另一个非常重要的竞态检查。

#### 为什么 permit 拿到后还要再次检查 `isPending()`

因为在：

- Lua claim 成功
- permit 真正拿到

这两步之间，仍然可能发生：

- 用户关闭页面，触发 `cancel()`
- 排队超时，触发 `timeout()`

也就是说：

- 即使 permit 已经拿到了
- 这个请求也可能已经不应该再执行业务

#### 这时为什么要立刻释放 permit

```java
releasePermitQuietly(permitId);
```

因为这个 permit 已经不属于一个“有效等待请求”了。  
如果不释放：

- 会平白占住全局并发槽位
- 其他等待请求必须等到下一次 lease 到期或更晚才能继续

#### 为什么还要发通知

```java
publishQueueNotify();
```

因为刚刚释放了 permit，这意味着：

- 资源状态发生变化
- 后继等待者现在可能可以继续推进

如果不通知，其他请求可能只能靠下一次轮询才能发现资源已空出，增加延迟。

#### 返回 false 的含义

- 当前 ticket 这次推进已经作废
- 不会进入 `grant()`

---

### 6.7 第七步：真正推进到 `GRANTED`

```java
publishQueueNotify();
return ticket.grant(permitId);
```

到这里说明：

- ticket 仍处于 `PENDING`
- Lua 已允许当前请求 claim
- 真正 permit 已拿到
- 期间没有被取消、没有超时

这时才真正调用：

- `ticket.grant(permitId)`

#### 为什么在 `grant()` 前先发一次通知

因为一旦：

- 当前请求成功 claim 并拿到 permit

就意味着：

- 队列结构变了
- 全局资源状态也变了

提前通知其他等待者，可以加快：

- 其他节点重新评估队列
- 本地 poller 被唤醒

这种设计体现的是：

- **通知优先，轮询兜底**

#### `ticket.grant(permitId)` 做了什么

`grant()` 会完成：

1. 先写 `permitRef`
2. CAS 抢占 `PENDING -> GRANTED`
3. 注销 notifier / cancel future
4. 用包装任务把 `onAcquired` 提交到 `onAcquiredExecutor`
5. 最终由业务线程执行真正 chat 逻辑

也就是说，`tryAcquireIfReady()` 自己并不直接执行业务，它只是推进状态到可以执行。

---

## 7. 为什么必须把“claim 队列资格”和“获取 permit”拆成两步

这是理解整个方法的关键。

### 7.1 如果只看队列，不看 permit

会出现：

- 你明明轮到自己了
- 但全局资源已经被别的节点占满
- 仍然错误放行

### 7.2 如果只看 permit，不看队列

会出现：

- 谁先抢到 permit 谁就执行
- 队列顺序失去意义
- 公平性被破坏

### 7.3 两步拆开后的语义

- `claimIfReady(...)`
  - 负责公平性
- `tryAcquirePermit()`
  - 负责资源占用

中间再用补偿逻辑连接。

这是经典的：

- **公平排队**
- **资源占用**

双维度解耦设计。

---

## 8. 与哪些模块合作

### 8.1 与 `Ticket` 合作

它依赖 `Ticket` 提供：

- `isPending()`
- `requestId`
- `deadline`
- `grant()`

本质上：

- `tryAcquireIfReady()` 负责判断与推进
- `Ticket` 负责终态仲裁与资源所有权切换

### 8.2 与 Redis ZSET 队列合作

它通过：

- `claimIfReady(...)`
- `queue.add(...)`
- `queue.remove(...)`

操作公平队列。

### 8.3 与 entry marker 合作

它通过：

- `setEntryMarker(...)`
- `deleteEntryMarker(...)`

确保请求活性标记与队列状态一致。

### 8.4 与 Lua 脚本合作

Lua 脚本是 claim 阶段的原子协调器，负责：

- 存活窗口判定
- 僵尸清理
- 原子出队

### 8.5 与 `RPermitExpirableSemaphore` 合作

它负责实际资源占用：

- `availablePermits()`
- `tryAcquirePermit()`
- `releasePermitQuietly(...)`

### 8.6 与 `PollNotifier` 和 Redis Topic 合作

方法内部多处 `publishQueueNotify()`，说明它会主动推动：

- 本地等待者
- 其他节点等待者

及时重新尝试，而不是完全依赖固定周期轮询。

---

## 9. 这个方法解决了哪些关键并发问题

### 9.1 解决“同一请求被多次推进”

通过：

- 入口 `ticket.isPending()`
- permit 获取前后的二次 `isPending()` 检查
- 最终 `ticket.grant()` 的 CAS 抢终态

保证：

- 同一个 ticket 只会成功进入执行态一次

### 9.2 解决“claim 成功但资源没拿到”

通过：

- 记录 `claimedScore`
- 按原 score 重入队
- 重建 entry marker

保证：

- 不会丢请求
- 不会破坏公平性

### 9.3 解决“请求在关键窗口被取消/超时”

通过：

- claim 之后 permit 之前回查状态
- permit 之后 grant 之前回查状态

保证：

- 已终态请求不会误执行
- permit 会被及时释放

### 9.4 解决“僵尸条目卡队头”

通过：

- Lua 里根据 entry marker 清僵尸
- Java 侧重入队后再次回查状态

保证：

- 不会因为 race 重新制造永久占位的死条目

### 9.5 解决“资源释放后等待者感知过慢”

通过：

- 多处 `publishQueueNotify()`

保证：

- permit 变化和队列结构变化尽快传播
- 降低对轮询周期的依赖

---

## 10. 为什么它返回 false 的场景这么多

`tryAcquireIfReady()` 不是一个“成功/失败”方法，而更像：

- **一次非阻塞推进尝试**

所以返回 `false` 的原因很多，但大多都不代表异常，而只是：

- 现在还不该轮到你
- 或这次推进遇到了竞态，需要下次重试

具体包括：

- ticket 已终态
- 没有可用 permit
- Lua claim 不通过
- claim 成功但 permit 没拿到，需要回队
- permit 拿到了，但 ticket 已被取消/超时
- `ticket.grant(permitId)` 失败

所以你应该把这个返回值理解成：

- `true`：本轮成功推进进入执行态
- `false`：本轮没有推进成功，可能等待、补偿或终止

---

## 11. 用一个完整时序来理解

### 正常成功路径

1. Ticket 被唤醒
2. `isPending == true`
3. `availablePermits > 0`
4. Lua claim 成功，拿到 `claimedScore`
5. `tryAcquirePermit()` 返回 `permitId`
6. 再次确认 `isPending == true`
7. `publishQueueNotify()`
8. `ticket.grant(permitId)`
9. `onAcquired` 提交到业务线程池
10. 真正开始 chat

### claim 成功但 permit 失败路径

1. Ticket 被唤醒
2. `isPending == true`
3. `availablePermits > 0`
4. Lua claim 成功
5. permit 被其他节点先拿走，`permitId == null`
6. 重建 marker
7. 按原 `claimedScore` 回队
8. 发通知
9. 若中途已终态，再自回滚
10. 返回 false，等待下轮推进

### permit 拿到但中途取消路径

1. Ticket claim 成功
2. permit 获取成功
3. 但在窗口期，用户断开连接触发 `cancel()`
4. `ticket.isPending()` 再检查失败
5. 释放 permit
6. 发通知
7. 返回 false，不执行业务

---

## 12. 面试时怎么概括 tryAcquireIfReady

可以这样表达：

> `tryAcquireIfReady` 是我这套分布式公平限流里最核心的竞争函数。它先做本地快速判断，再通过 Lua 原子 claim 公平队列资格，随后尝试获取可过期 semaphore permit。由于 claim 和 acquire 之间存在多节点并发竞争，它又补上了原 score 重入队、entry marker 重建、终态二次回查和 permit 释放通知等补偿逻辑，保证在 cancel、timeout、线程池拒绝等多种竞态下，不丢请求、不破坏公平、不泄漏 permit，并且业务回调只会进入一次。 

---

## 13. 总结

`tryAcquireIfReady()` 是 `FairDistributedRateLimiter` 中最关键的方法。  
它把以下几件事统一协调在了一次“非阻塞推进尝试”里：

- Ticket 是否仍然有效
- 当前是否存在可用并发资源
- 当前请求是否位于公平窗口中
- claim 与 permit 获取之间的竞态补偿
- permit 释放与后继请求推进通知
- 最终向 `GRANTED` 的安全推进

从工程角度看，这个方法最体现价值的地方在于：

- 不是简单“轮到你了就执行”
- 而是把分布式公平性、并发资源控制、状态机终态竞争和异常补偿统一整合进了一个严格的推进过程

如果一句话总结：

> `tryAcquireIfReady()` 解决的是“一个等待中的 Ticket 现在能否被安全、公平、无资源泄漏地推进到执行态”这个问题。  

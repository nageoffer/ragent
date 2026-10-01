/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.framework.web;

import cn.hutool.core.util.StrUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式任务跨节点取消管理器
 * <p>
 * 机制层与引擎无关：系统侧取消直接处理本地注册表，用户主动停止通过 Redis 标记与广播跨节点投递，
 * 各引擎的个性化收尾动作（补发事件、中断上游流等）以回调形式注入
 */
@Slf4j
@Component
public class StreamTaskManager {

    private static final String CANCEL_TOPIC = "ragent:stream:cancel";
    private static final String CANCEL_KEY_PREFIX = "ragent:stream:cancel:";
    private static final String OWNER_KEY_PREFIX = "ragent:stream:owner:";
    private static final Duration CANCEL_TTL = Duration.ofMinutes(30);

    /**
     * 系统侧回收的发起方占位，与任何用户 ID 都不会撞（用户 ID 是雪花数字串）
     */
    private static final String SYSTEM_REQUESTER = "__system__";

    /**
     * 广播载荷 taskId|requester 的分隔符：taskId 与用户 ID 都是雪花数字串，不含竖线
     */
    private static final String PAYLOAD_SEPARATOR = "|";

    private final Cache<String, StreamTaskInfo> tasks = CacheBuilder.newBuilder()
            .expireAfterWrite(CANCEL_TTL)
            .maximumSize(10000)  // 限制最大数量，基本上不可能超出这个数量。如果觉得不稳妥，可以把值调大并在配置文件声明
            .build();

    private final RedissonClient redissonClient;
    private int listenerId = -1;

    public static Duration taskRetention() {
        return CANCEL_TTL;
    }

    public StreamTaskManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @PostConstruct
    public void subscribe() {
        RTopic topic = redissonClient.getTopic(CANCEL_TOPIC);
        listenerId = topic.addListener(String.class, (channel, payload) -> {
            if (StrUtil.isBlank(payload)) {
                return;
            }
            int separator = payload.indexOf(PAYLOAD_SEPARATOR);
            // 滚动升级期老节点仍广播裸 taskId，它在发布前已做过同样的属主校验，按系统侧收
            String taskId = separator < 0 ? payload : payload.substring(0, separator);
            String requester = separator < 0 ? SYSTEM_REQUESTER : payload.substring(separator + 1);
            cancelLocal(taskId, requester);
        });
    }

    @PreDestroy
    public void unsubscribe() {
        if (listenerId == -1) {
            return;
        }
        redissonClient.getTopic(CANCEL_TOPIC).removeListener(listenerId);
    }

    /**
     * 注册取消收尾回调并记下属主，回调负责补发终止事件并结束响应流
     * 若任务已被属主或系统标记取消，回调立即执行
     */
    public void register(String taskId, String ownerUserId, Runnable onCancelFinalizer) {
        StreamTaskInfo taskInfo = getOrCreate(taskId);
        // 属主必须先于收尾回调落地：反过来的话，两条赋值之间到达的广播能拿本地回调杀掉一条无主的流
        taskInfo.ownerUserId = ownerUserId;
        taskInfo.finalizer = onCancelFinalizer;
        // 属主进 Redis 而非只留本地：停止请求可能落在没跑这条流的节点上
        if (StrUtil.isNotBlank(ownerUserId)) {
            RBucket<String> owner = redissonClient.getBucket(ownerKey(taskId));
            owner.set(ownerUserId, CANCEL_TTL);
        }
        if (isTaskCancelledInRedis(taskId, taskInfo)) {
            onCancelFinalizer.run();
        }
    }

    /**
     * 绑定上游流的中断动作，取消时先于收尾回调执行
     * 任务已结算注销时不再创建记录，否则会留下一条无人触发的幽灵任务
     */
    public void bindHandle(String taskId, Runnable cancelAction) {
        StreamTaskInfo taskInfo = tasks.getIfPresent(taskId);
        if (taskInfo == null) {
            return;
        }
        taskInfo.cancelAction = cancelAction;
        if (taskInfo.cancelled.get() && cancelAction != null) {
            cancelAction.run();
        }
    }

    public boolean isCancelled(String taskId) {
        StreamTaskInfo info = tasks.getIfPresent(taskId);
        return info != null && info.cancelled.get();
    }

    /**
     * 系统侧回收（SSE 超时、客户端断连），容器回调线程上没有登录用户可比对
     */
    public void cancel(String taskId) {
        // SSE 生命周期回调与任务在同一个 JVM，无需依赖 Redis/MQ 往返
        cancelLocal(taskId, SYSTEM_REQUESTER);
    }

    /**
     * 用户主动停止：taskId 是雪花 ID，时间有序可预测，不是访问凭证，必须比对属主
     */
    public void cancelByUser(String taskId) {
        String requester = UserContext.requireUser().getUserId();
        RBucket<String> owner = redissonClient.getBucket(ownerKey(taskId));
        String ownerUserId = owner.get();
        if (StrUtil.isNotBlank(ownerUserId) && !ownerUserId.equals(requester)) {
            log.warn("拒绝越权停止流式任务，taskId：{}，属主：{}，发起方：{}", taskId, ownerUserId, requester);
            // 不区分「不存在」与「非属主」，免得停止接口变成他人任务的探测器
            throw new ClientException("任务不存在或已结束");
        }
        // 属主查不到多半是任务已结束，也可能是注册还没落地，故标记带上发起方交给注册那一刻复核
        publishCancel(taskId, requester);
    }

    private void publishCancel(String taskId, String requester) {
        // 先设置 Redis 标记，再发布消息
        RBucket<String> bucket = redissonClient.getBucket(cancelKey(taskId));
        bucket.set(requester, CANCEL_TTL);

        // 发布消息通知所有节点（包括本地）
        // 本地节点也通过监听器统一处理，避免重复调用 cancelLocal
        // 载荷带上发起方：执行端要靠它复核，只有发布端校验挡不住属主落地前的抢跑
        redissonClient.getTopic(CANCEL_TOPIC).publish(taskId + PAYLOAD_SEPARATOR + requester);
    }

    /**
     * 检查任务是否在 Redis 中被标记为已取消，是则同步状态到本地缓存
     * 标记可能先于注册到达，那一刻还没有属主可比对，只能推到这里复核
     */
    private boolean isTaskCancelledInRedis(String taskId, StreamTaskInfo taskInfo) {
        if (taskInfo.cancelled.get()) {
            return true;
        }

        RBucket<String> bucket = redissonClient.getBucket(cancelKey(taskId));
        String requester = bucket.get();
        if (requester == null) {
            return false;
        }
        if (isRequesterDenied(taskInfo, requester)) {
            log.warn("忽略非属主埋下的取消标记，taskId：{}，属主：{}，发起方：{}", taskId, taskInfo.ownerUserId, requester);
            return false;
        }
        taskInfo.cancelled.set(true);
        return true;
    }

    /**
     * 判断是否拒绝发起方：系统侧回收不拒绝；用户侧非属主或属主还没落地（注册未发生）时拒绝
     * 这正是预埋标记要在 register 那一刻复核的窗口，本地放过去反而绕开了复核
     */
    private boolean isRequesterDenied(StreamTaskInfo taskInfo, String requester) {
        if (SYSTEM_REQUESTER.equals(requester)) {
            return false;
        }
        return StrUtil.isBlank(taskInfo.ownerUserId) || !taskInfo.ownerUserId.equals(requester);
    }

    private void cancelLocal(String taskId, String requester) {
        StreamTaskInfo taskInfo = tasks.getIfPresent(taskId);
        if (taskInfo == null) {
            return;
        }

        // 执行端复核发起方：taskId 时间有序可预测，越权取消喷得中就成
        // 不匹配时连 cancelled 都不置——置了会让 register 的复核短路，等于把标记复核那道门绕开
        if (isRequesterDenied(taskInfo, requester)) {
            log.warn("拒绝越权取消流式任务，taskId：{}，属主：{}，发起方：{}", taskId, taskInfo.ownerUserId, requester);
            return;
        }

        // 使用 CAS 确保只执行一次
        if (!taskInfo.cancelled.compareAndSet(false, true)) {
            return;
        }

        if (taskInfo.cancelAction != null) {
            taskInfo.cancelAction.run();
        }

        // 在取消时执行收尾回调，保存已累积的内容
        if (taskInfo.finalizer != null) {
            taskInfo.finalizer.run();
        }
    }

    public void unregister(String taskId) {
        // 清理本地缓存
        tasks.invalidate(taskId);

        // 清理Redis
        redissonClient.getBucket(cancelKey(taskId)).deleteAsync();
        redissonClient.getBucket(ownerKey(taskId)).deleteAsync();
    }

    private String cancelKey(String taskId) {
        return CANCEL_KEY_PREFIX + taskId;
    }

    private String ownerKey(String taskId) {
        return OWNER_KEY_PREFIX + taskId;
    }

    @SneakyThrows
    private StreamTaskInfo getOrCreate(String taskId) {
        return tasks.get(taskId, StreamTaskInfo::new);
    }

    private static final class StreamTaskInfo {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile String ownerUserId;
        private volatile Runnable cancelAction;
        private volatile Runnable finalizer;
    }
}

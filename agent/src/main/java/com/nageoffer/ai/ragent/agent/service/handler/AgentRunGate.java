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

package com.nageoffer.ai.ragent.agent.service.handler;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 用户维度的 Agent 并发闸门：一个用户同一时刻只跑一条流
 * 与 @IdempotentSubmit 的区别是覆盖整个流生命周期，而非控制器返回 emitter 前的同步窗口
 */
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentRunGate {

    private static final String RUNNING_KEY_PREFIX = "ragent:agent:running:";

    /**
     * 运行位存 taskId|conversationId：删会话时要凭它认出该停的是哪条流，两段都是雪花数字串，不含竖线
     */
    private static final String SLOT_SEPARATOR = "|";

    private final RedissonClient redissonClient;
    private final AgentProperties agentProperties;

    /**
     * 抢运行位，抢不到直接拒绝；返回的释放动作由调用方挂到收尾路上
     */
    public Runnable acquire(String userId, String taskId, String conversationId) {
        //RBucket 对应 Redis String 数据类型，就是一个 key‑value 桶
        String slotValue = taskId + SLOT_SEPARATOR + conversationId;
        RBucket<String> slot = redissonClient.getBucket(runningKey(userId));
        //基于 SET‑NX EX 实现的自定义分布式状态标记 --->用户级别互斥：同一个 userId，同一时间只能跑一个会话
        if (!slot.setIfAbsent(slotValue, ttl())) {
            throw new ClientException("当前会话处理中，请稍后再发起新的对话");
        }
        return () -> release(userId, slotValue);
    }

    /**L
     * 该用户此刻正跑的流若属于这个会话，返回它的 taskId，否则返回 null
     * 运行位的取值格式只有闸门自己知道，外部拿到的始终是 taskId
     */
    public String runningTaskId(String userId, String conversationId) {
        RBucket<String> slot = redissonClient.getBucket(runningKey(userId));    //返回Bucket object = Bucket 本身这个实例对象
        String slotValue = slot.get();
        if (StrUtil.isBlank(slotValue)) {
            return null;
        }
        int separator = slotValue.indexOf(SLOT_SEPARATOR);  //查找分隔符"|"在字符串里第一次出现的下标位置，≥0：找到了，返回字符索引位置； ‑1：字符串里根本没有这个分隔符
        if (separator < 0 || !slotValue.substring(separator + 1).equals(conversationId)) {
            return null;
        }
        //有分隔符 并且 后半段完全等于 conversationId，才返回 taskId
        return slotValue.substring(0, separator);
    }

    /**
     * 只放自己占的槽位：运行位若被 TTL 挤掉又被下一轮抢走，无条件删会把别人的闸门放掉
     * 重复调用天然安全，值对不上就是空操作
     */
    private void release(String userId, String slotValue) {
        RBucket<String> slot = redissonClient.getBucket(runningKey(userId));
        slot.compareAndSet(slotValue, null);//compareAndSet()方法是原子操作，只有当前值等于slotValue时才会将其设置为null，否则不做任何操作
    }

    /**
     * 进程崩溃、线程异常死掉，正常的 `release()` 根本不会执行，Redis 的 running 槽 key 会永久残留在 Redis，导致用户被锁住无法发起新对话。TTL 过期自动删除是兜底手段。
     * 取 SSE 超时的两倍：长过任何一条活着的流，又不至于把用户挡到下个小时
     * 
     */
    private Duration ttl() {
        return Duration.ofMillis(agentProperties.getSseTimeoutMs() * 2);
    }

    private String runningKey(String userId) {
        return RUNNING_KEY_PREFIX + userId;
    }
}

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

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletionException;

/**
 * 控制 Agent 运行并发：同一会话互斥，并限制单用户并发会话数
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentRunGate {

    private static final String CONVERSATION_KEY_PREFIX = "ragent:agent:run-lock:";
    private static final String PERMIT_KEY_PREFIX = "ragent:agent:run-permit:";

    private final RedissonClient redissonClient;
    private final AgentProperties agentProperties;

    public Runnable acquire(String userId, String taskId, String conversationId) {
        long owner = Long.parseLong(taskId);
        RLock conversation = redissonClient.getLock(conversationKey(userId, conversationId));
        if (!tryAcquire(conversation, owner)) {
            throw new ClientException("当前会话正在处理中，请稍后重试");
        }
        try {
            int limit = agentProperties.getMaxConcurrentRunsPerUser();
            for (int slot = 0; slot < limit; slot++) {
                RLock permit = redissonClient.getLock(PERMIT_KEY_PREFIX + userId + ":" + slot);
                if (tryAcquire(permit, owner)) {
                    return () -> {
                        unlock(permit, owner);
                        unlock(conversation, owner);
                    };
                }
            }
            throw new ClientException("并发会话已达上限，请稍后重试");
        } catch (RuntimeException | Error e) {
            unlock(conversation, owner);
            throw e;
        }
    }

    public boolean isRunning(String userId, String conversationId) {
        return redissonClient.getLock(conversationKey(userId, conversationId)).isLocked();
    }

    private boolean tryAcquire(RLock lock, long owner) {
        return lock.tryLockAsync(owner).toCompletableFuture().join();
    }

    private void unlock(RLock lock, long owner) {
        try {
            lock.unlockAsync(owner).toCompletableFuture().join();
        } catch (RuntimeException e) {
            Throwable cause = e instanceof CompletionException ? e.getCause() : e;
            if (!(cause instanceof IllegalMonitorStateException)) {
                log.error("Agent锁释放失败，key: {}, owner: {}", lock.getName(), owner, e);
            }
        }
    }

    private String conversationKey(String userId, String conversationId) {
        return CONVERSATION_KEY_PREFIX + userId + ":" + conversationId;
    }
}

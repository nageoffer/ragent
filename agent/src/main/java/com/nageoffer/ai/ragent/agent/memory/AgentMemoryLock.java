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

package com.nageoffer.ai.ragent.agent.memory;

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 用户级记忆锁：主动整理与后台学习改同一份记忆，读记忆、调 Judge、提交期间都持有它
 * 不设租期，由 Redisson 看门狗续期；加锁与解锁须在同一线程
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentMemoryLock {

    private static final String KEY_PREFIX = "ragent:agent:memory-lock:";

    private final RedissonClient redissonClient;

    /**
     * 不等待：拿到返回锁，拿不到返回 null，由调用方报 BUSY
     */
    protected RLock tryAcquire(String userId) {
        RLock lock = redissonClient.getLock(KEY_PREFIX + userId);
        return lock.tryLock() ? lock : null;
    }

    /**
     * 放锁失败只记错误日志，不改本次结局
     */
    protected void release(RLock lock, String userId) {
        try {
            lock.unlock();
        } catch (RuntimeException e) {
            log.warn("长期记忆锁释放失败, userId: {}", userId, e);
        }
    }
}

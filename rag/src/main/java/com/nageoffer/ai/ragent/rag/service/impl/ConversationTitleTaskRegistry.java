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

package com.nageoffer.ai.ragent.rag.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 会话标题异步生成任务登记表
 * <p>
 * 创建会话时异步生成标题，回答结束前由流式处理器按需等待一小段时间，
 * 让多数情况下 FINISH 事件能带上 LLM 生成的标题，而不是占位标题。
 */
@Slf4j
@Component
public class ConversationTitleTaskRegistry {

    private final Map<String, CompletableFuture<?>> tasks = new ConcurrentHashMap<>();

    /**
     * 登记标题生成任务，任务结束后自动移除
     */
    public void register(String userId, String conversationId, CompletableFuture<?> future) {
        String key = key(userId, conversationId);
        tasks.put(key, future);
        future.whenComplete((result, ex) -> tasks.remove(key, future));
    }

    /**
     * 最多等待 timeoutMillis 毫秒让标题生成任务结束；无任务、超时或失败均直接返回
     */
    public void await(String userId, String conversationId, long timeoutMillis) {
        CompletableFuture<?> future = tasks.get(key(userId, conversationId));
        if (future == null) {
            return;
        }
        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            log.debug("等待会话标题生成超时，conversationId：{}", conversationId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warn("等待会话标题生成失败，conversationId：{}", conversationId, ex);
        }
    }

    private String key(String userId, String conversationId) {
        return userId + ":" + conversationId;
    }
}

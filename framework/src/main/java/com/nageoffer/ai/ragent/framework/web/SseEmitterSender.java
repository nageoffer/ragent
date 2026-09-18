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

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE（Server-Sent Events）发送器封装类
 *
 * <p>该类对 Spring 的 SseEmitter 进行封装，提供了线程安全的事件发送功能，
 * 统一处理连接关闭状态和异常情况。主要用于服务端向客户端推送实时数据流</p>
 */
@Slf4j
public class SseEmitterSender {

    /**
     * Spring SSE 发送器实例
     */
    private final SseEmitter emitter;

    /**
     * 连接关闭状态标识，使用原子布尔类型保证线程安全
     * true 表示连接已关闭，false 表示连接仍然活跃
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Spring 的 SseEmitter 实例，用于实际的 SSE 通信
     */
    public SseEmitterSender(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));
    }

    /**
     * 发送 SSE 事件到客户端
     *
     * <p>支持两种发送模式：</p>
     * <ul>
     *   <li>当 eventName 为 null 时，使用默认事件格式发送数据</li>
     *   <li>当 eventName 不为 null 时，发送带命名的事件</li>
     * </ul>
     *
     * @param eventName 事件名称，为 null 时使用默认格式
     * @param data      要发送的数据内容
     */
    public void sendEvent(String eventName, Object data) {
        if (closed.get()) {
            return;
        }
        try {
            if (eventName == null) {
                emitter.send(data);
                return;
            }
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            closed.set(true);
            log.warn("SSE connection closed while sending event", e);
        }
    }

    /**
     * 正常完成并关闭 SSE 连接
     *
     * <p>使用 CAS 操作确保连接只被关闭一次，避免重复关闭导致的问题
     * 该方法是幂等的，多次调用只有第一次会生效</p>
     */
    public void complete() {
        // 使用 CAS 原子操作，确保只关闭一次
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

}

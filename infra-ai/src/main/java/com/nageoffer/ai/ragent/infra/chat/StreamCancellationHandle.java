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

package com.nageoffer.ai.ragent.infra.chat;

/**
 * 流式取消句柄
 * <p>
 * 用途说明：
 * - 用于控制大模型或 RAG 系统的流式输出过程
 * - 调用方可在任意时间主动取消流式推理（如用户点击“停止生成”）
 * - 通常由底层流式执行引擎（LLM Client、SSE、WebSocket）创建并返回
 * <p>
 * 使用场景：
 * - 流式问答中用户点击“停止回答”
 * - 后端超时、业务需要提前取消推理
 * - 配合 StreamCallback 一起使用，确保中断后不再继续 onContent() 回调
 * <p>
 * 注意事项：
 * - cancel() 应保证幂等，即多次调用不会导致异常
 * - 取消后应确保底层模型推理取消并释放资源（线程、连接等）
 */
public interface StreamCancellationHandle {

    /**
     * 取消当前流式推理任务
     * <p>
     * 说明：
     * - 调用后应立即尝试取消底层模型生成过程
     * - 常用于用户主动取消（例如 ChatGPT 中的“Stop generating”）
     * - 调用后应该不会再继续产生 onContent() 回调
     */
    void cancel();

    /**
     * 释放调用线程上的 trace 资源（从 NODE_STACK 弹出节点）
     * <p>
     * 默认实现为空，向后兼容不需要 trace 清理的实现。
     * 需要 trace 支持的实现应覆写此方法，确保 provider 节点的 span 在合适时机
     * （如首包探测完成后）从线程栈中弹出
     */
    default void detach() {}
}

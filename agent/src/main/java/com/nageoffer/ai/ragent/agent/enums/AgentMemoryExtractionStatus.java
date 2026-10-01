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

package com.nageoffer.ai.ragent.agent.enums;

/**
 * 长期记忆抽取任务的处理状态
 * WRITTEN、NOOP 和 DROPPED 表示本批消息已处理，后续抽取会跳过这些消息
 */
public enum AgentMemoryExtractionStatus {

    /**
     * 正在处理，同一用户同时只能有一个抽取任务处于此状态
     */
    PROCESSING,

    /**
     * 处理完成，已执行本批次的记忆变更
     */
    WRITTEN,

    /**
     * 处理完成，本批次没有生效的记忆决策
     */
    NOOP,

    /**
     * 处理失败且已达到最大尝试次数，跳过本批消息，继续处理后续消息
     */
    DROPPED,

    /**
     * 本次处理未完成，等待重试，包括提交时数据已变化、处理失败或超时
     * 其中，提交时数据已变化的情况不计入尝试次数
     */
    CONFLICT
}

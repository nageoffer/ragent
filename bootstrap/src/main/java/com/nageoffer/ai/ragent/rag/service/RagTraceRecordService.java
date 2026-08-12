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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;

import java.util.Date;

/**
 * RAG Trace 记录服务
 */
public interface RagTraceRecordService {

    void startRun(RagTraceRunDO run);

    void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs);

    /**
     * 将指定任务仍处于 RUNNING 的 trace run 收尾为 CANCELLED
     * <p>
     * 取消信号在 provider client 层被拦截（{@code ForwardingStreamCallback} 对外部取消不透传 delegate，
     * 否则流式 failover 切换候选时会终止用户 SSE），run 级终态无法由回调链驱动，只能按 taskId 单独上报
     * </p>
     *
     * @param taskId  流式任务 ID
     * @param endTime 取消发生的时间
     * @return 是否确实有一行由 RUNNING 翻转为 CANCELLED
     */
    boolean cancelRunByTaskId(String taskId, Date endTime);

    void startNode(RagTraceNodeDO node);

    void finishNode(String traceId, String nodeId, String status, String errorMessage, Date endTime, long durationMs);
}

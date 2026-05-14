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

package com.nageoffer.ai.ragent.rag.trace;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 流式对话 Trace 包装器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamChatTraceRunner {

    private static final String ENTRY_METHOD = "RAGChatService#streamChat";
    private static final String TRACE_NAME = "rag-stream-chat";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";

    private final RagTraceProperties traceProperties;
    private final RagTraceRecordService traceRecordService;

    public void run(String question,
                    String conversationId,
                    String taskId,
                    StreamCallback callback,
                    Runnable businessLogic) {
        if (!traceProperties.isEnabled()) {
            runWithoutTrace(conversationId, taskId, callback, businessLogic);
            return;
        }

        String traceId = IdUtil.getSnowflakeNextIdStr();
        long startMillis = System.currentTimeMillis();
        traceRecordService.startRun(RagTraceRunDO.builder()
                .traceId(traceId)
                .traceName(TRACE_NAME)
                .entryMethod(ENTRY_METHOD)
                .conversationId(conversationId)
                .taskId(taskId)
                .userId(UserContext.getUserId())
                .status(STATUS_RUNNING)
                .startTime(new Date())
                .extraData(StrUtil.format("{\"questionLength\":{}}", StrUtil.length(question)))
                .build());

        RagTraceContext.setTraceId(traceId);
        RagTraceContext.setTaskId(taskId);
        try {
            businessLogic.run();
            traceRecordService.finishRun(
                    traceId,
                    STATUS_SUCCESS,
                    null,
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
        } catch (Throwable ex) {
            traceRecordService.finishRun(
                    traceId,
                    STATUS_ERROR,
                    truncateError(ex),
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
            log.warn("执行流式对话失败，会话ID：{}，任务ID：{}", conversationId, taskId, ex);
            callback.onError(ex);
        } finally {
            RagTraceContext.clear();
        }
    }

    private void runWithoutTrace(String conversationId,
                                 String taskId,
                                 StreamCallback callback,
                                 Runnable businessLogic) {
        try {
            businessLogic.run();
        } catch (Throwable ex) {
            log.warn("执行流式对话失败，会话ID：{}，任务ID：{}", conversationId, taskId, ex);
            callback.onError(ex);
        }
    }

    private String truncateError(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = throwable.getClass().getSimpleName() + ": " + StrUtil.blankToDefault(throwable.getMessage(), "");
        int max = traceProperties.getMaxErrorLength();
        return message.length() <= max ? message : message.substring(0, max);
    }
}

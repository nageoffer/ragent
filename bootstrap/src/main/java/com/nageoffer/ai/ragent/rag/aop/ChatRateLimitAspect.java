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

package com.nageoffer.ai.ragent.rag.aop;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;

/**
 * SSE 全局限流切面，避免业务代码侵入
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ChatRateLimitAspect {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";

    private final ChatQueueLimiter chatQueueLimiter;
    private final RagTraceProperties ragTraceProperties;
    private final RagTraceRecordService traceRecordService;

    @Around("@annotation(com.nageoffer.ai.ragent.rag.aop.ChatRateLimit)")
    public Object limitStreamChat(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length < 4 || !(args[3] instanceof SseEmitter emitter)) {
            return joinPoint.proceed();
        }

        String question = args[0] instanceof String q ? q : "";
        String conversationId = args[1] instanceof String cid ? cid : null;
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        args[1] = actualConversationId;
        Object target = joinPoint.getTarget();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        chatQueueLimiter.enqueue(question, actualConversationId, emitter, () -> {
            invokeWithTrace(method, target, args, question, actualConversationId, emitter);
        });
        return null;
    }

    private void invokeWithTrace(Method method,
                                 Object target,
                                 Object[] args,
                                 String question,
                                 String conversationId,
                                 SseEmitter emitter) {
        if (!ragTraceProperties.isEnabled()) {
            invokeTarget(method, target, args, emitter);
            return;
        }

        String runId = IdUtil.getSnowflakeNextIdStr();
        String taskId = IdUtil.getSnowflakeNextIdStr();
        long startMillis = System.currentTimeMillis();
        traceRecordService.startRun(RagTraceRunDO.builder()
                .runId(runId)
                .traceName("rag-enterprise-stream-chat")
                .entryMethod(method.getDeclaringClass().getName() + "#" + method.getName())
                .conversationId(conversationId)
                .taskId(taskId)
                .userId(UserContext.getUserId())
                .status(STATUS_RUNNING)
                .startTime(new Date())
                .extraData(StrUtil.format("{\"questionLength\":{}}", StrUtil.length(question)))
                .build());

        RagTraceContext.setRunId(runId);
        RagTraceContext.setTaskId(taskId);
        try {
            method.invoke(target, args);
            traceRecordService.finishRun(
                    runId,
                    STATUS_SUCCESS,
                    null,
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
        } catch (Throwable ex) {
            Throwable cause = unwrap(ex);
            traceRecordService.finishRun(
                    runId,
                    STATUS_ERROR,
                    truncateError(cause),
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
            log.warn("执行流式对话失败", cause);
            emitter.completeWithError(cause);
        } finally {
            RagTraceContext.clear();
        }
    }

    private void invokeTarget(Method method, Object target, Object[] args, SseEmitter emitter) {
        try {
            method.invoke(target, args);
        } catch (Throwable ex) {
            Throwable cause = unwrap(ex);
            log.warn("执行流式对话失败", cause);
            emitter.completeWithError(cause);
        }
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocationTargetException
                && invocationTargetException.getTargetException() != null) {
            return invocationTargetException.getTargetException();
        }
        return throwable;
    }

    private String truncateError(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = throwable.getClass().getSimpleName() + ": " + StrUtil.blankToDefault(throwable.getMessage(), "");
        if (message.length() <= ragTraceProperties.getMaxErrorLength()) {
            return message;
        }
        return message.substring(0, ragTraceProperties.getMaxErrorLength());
    }
}

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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 全局限流切面，避免业务代码侵入
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ChatRateLimitAspect {

    private final ChatQueueLimiter chatQueueLimiter;

    @Around("@annotation(com.nageoffer.ai.ragent.rag.aop.ChatRateLimit)")
    public Object limitStreamChat(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length < 4 || !(args[3] instanceof SseEmitter emitter)) {
            return joinPoint.proceed();
        }

        String question = args[0] instanceof String q ? q : "";
        String conversationId = args[1] instanceof String cid ? cid : null;
        Object target = joinPoint.getTarget();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();

        chatQueueLimiter.enqueue(question, conversationId, emitter, () -> {
            try {
                signature.getMethod().invoke(target, args);
            } catch (Throwable ex) {
                log.warn("执行流式对话失败", ex);
                emitter.completeWithError(ex);
            }
        });
        return null;
    }
}

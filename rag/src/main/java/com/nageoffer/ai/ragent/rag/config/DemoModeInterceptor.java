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

package com.nageoffer.ai.ragent.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.PrintWriter;
import java.util.Set;

/**
 * 体验环境只读模式拦截器
 */
@Component
@RequiredArgsConstructor
public class DemoModeInterceptor implements HandlerInterceptor {

    private final DemoModeProperties demoModeProperties;
    private final ObjectMapper objectMapper;

    private static final String DEMO_REJECT_MESSAGE = "体验环境仅支持查询操作";

    /**
     * 需要拦截的 SSE 流式接口路径
     */
    private static final Set<String> SSE_PATHS = Set.of("/rag/v3/chat");

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        if (!demoModeProperties.getDemoMode()) {
            return true;
        }
        String method = request.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean isSsePath = SSE_PATHS.contains(path);
        if ("GET".equalsIgnoreCase(method) && !isSsePath) {
            return true;
        }
        if (isSsePath) {
            writeSseReject(response);
        } else {
            writeJsonReject(response);
        }
        return false;
    }

    private void writeSseReject(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        PrintWriter writer = response.getWriter();
        writer.write("event: reject\ndata: " + objectMapper.writeValueAsString(new MessageDelta("response", DEMO_REJECT_MESSAGE)) + "\n\n");
        writer.write("event: done\ndata: \"[DONE]\"\n\n");
        writer.flush();
    }

    private void writeJsonReject(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        Result<Void> result = new Result<Void>()
                .setCode(BaseErrorCode.CLIENT_ERROR.code())
                .setMessage(DEMO_REJECT_MESSAGE);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}

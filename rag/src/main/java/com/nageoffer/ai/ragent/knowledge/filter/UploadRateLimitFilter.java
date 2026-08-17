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

package com.nageoffer.ai.ragent.knowledge.filter;

import com.nageoffer.ai.ragent.knowledge.config.RagSemaphoreProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 文件上传限流 Filter
 * 在 multipart 解析之前拦截，防止临时文件产生
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class UploadRateLimitFilter extends OncePerRequestFilter {

    private final RedissonClient redissonClient;
    private final RagSemaphoreProperties semaphoreProperties;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        // 判断是否是上传请求
        if (!isUploadRequest(request)) {
            chain.doFilter(request, response);
            return;
        }

        // 获取信号量配置
        RagSemaphoreProperties.PermitExpirableConfig config = semaphoreProperties.getDocumentUpload();
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(config.getName());

        // 尝试获取许可
        String permitId = null;
        try {
            permitId = semaphore.tryAcquire(
                    config.getMaxWaitSeconds(),
                    config.getLeaseSeconds(),
                    TimeUnit.SECONDS
            );

            if (permitId == null) {
                // 获取许可失败，直接返回 429
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":\"429\",\"message\":\"当前上传人数过多，请稍后再试\"}");
                return; // 不调用 chain.doFilter()，请求到此为止
            }

            // 获取许可成功，继续处理请求
            chain.doFilter(request, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.setStatus(500);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"500\",\"message\":\"获取上传许可失败\"}");
        } finally {
            if (permitId != null) {
                boolean released = semaphore.tryRelease(permitId);
                if (!released) {
                    log.warn("upload permit already expired or released, permitId={}", permitId);
                }
            }
        }
    }

    private static final String UPLOAD_PATH_PATTERN = "/knowledge-base/";
    private static final String UPLOAD_PATH_SUFFIX = "/docs/upload";

    /**
     * 判断是否是文档上传请求
     */
    private boolean isUploadRequest(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        return uri != null && uri.contains(UPLOAD_PATH_PATTERN) && uri.endsWith(UPLOAD_PATH_SUFFIX);
    }
}
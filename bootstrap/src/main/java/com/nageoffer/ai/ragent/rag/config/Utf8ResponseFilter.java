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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 统一设置响应编码为 UTF-8 的过滤器
 *
 * <p>
 * 场景说明：
 * 在某些环境下，如果不显式设置响应字符编码，可能会出现中文乱码或不同组件之间
 * 使用的默认编码不一致的问题。通过此过滤器可以在所有响应返回前，强制设置为 UTF-8
 * </p>
 *
 * <p>
 * 使用方式：
 * 标注 {@link Component} 后，Spring Boot 会自动将其注册为 Servlet Filter，
 * 对所有请求生效（除非另外配置 FilterRegistrationBean 进行路径/顺序控制）
 * </p>
 */
@Component
public class Utf8ResponseFilter implements Filter {

    /**
     * 设置响应编码为 UTF-8 并继续执行过滤器链
     *
     * @param request  当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param chain    过滤器链，用于将请求/响应传递给下一个过滤器或最终的 Servlet
     * @throws IOException      I/O 读写异常
     * @throws ServletException Servlet 级别异常
     */
    @Override
    public void doFilter(ServletRequest request,
                         ServletResponse response,
                         FilterChain chain)
            throws IOException, ServletException {

        // 在响应写出之前，统一强制设置字符编码为 UTF-8
        response.setCharacterEncoding("UTF-8");

        // 放行请求，继续后续过滤器或最终目标处理
        chain.doFilter(request, response);
    }
}


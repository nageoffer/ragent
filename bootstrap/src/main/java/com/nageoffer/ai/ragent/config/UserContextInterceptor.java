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

package com.nageoffer.ai.ragent.config;

import cn.dev33.satoken.stp.StpUtil;
import com.nageoffer.ai.ragent.dao.entity.UserDO;
import com.nageoffer.ai.ragent.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 用户上下文拦截器
 *
 * <p>该拦截器用于在请求处理前从 SaToken 中获取登录用户信息，并设置到 UserContext 中，
 * 方便后续业务逻辑使用。在请求完成后清理 UserContext，避免内存泄漏。
 *
 * <p>主要功能：
 * <ul>
 *   <li>在请求前置处理时，从 SaToken 获取登录用户 ID</li>
 *   <li>根据用户 ID 查询数据库获取完整用户信息</li>
 *   <li>将用户信息封装成 LoginUser 对象并设置到 UserContext 线程上下文中</li>
 *   <li>在请求完成后清理 UserContext，防止线程复用时的数据污染</li>
 *   <li>跳过异步调度请求（如 SSE 完成回调），避免 SaToken 上下文丢失问题</li>
 * </ul>
 *
 * @author nageoffer
 * @see UserContext
 * @see LoginUser
 */
@Component
@RequiredArgsConstructor
public class UserContextInterceptor implements HandlerInterceptor {

    private final UserMapper userMapper;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) {
        // 异步调度请求跳过（SSE 完成回调会触发 asyncDispatch，此时 SaToken 上下文已丢失）
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        // 预检请求放行，避免 CORS 阻断
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String loginId = StpUtil.getLoginIdAsString();
        UserDO user = userMapper.selectById(Long.parseLong(loginId));

        UserContext.set(
                LoginUser.builder()
                        .userId(user.getId().toString())
                        .username(user.getUsername())
                        .role(user.getRole())
                        .build()
        );
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler, Exception ex) {
        UserContext.clear();
    }
}

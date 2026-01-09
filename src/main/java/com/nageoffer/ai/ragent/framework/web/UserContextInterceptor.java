package com.nageoffer.ai.ragent.framework.web;

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

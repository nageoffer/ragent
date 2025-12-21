package com.nageoffer.ai.ragent.framework.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.nageoffer.ai.ragent.framework.exception.ClientException;

/**
 * 用户上下文容器（基于 TTL 传递当前线程的登录用户）
 */
public final class UserContext {

    private static final TransmittableThreadLocal<LoginUser> CONTEXT = new TransmittableThreadLocal<>();

    /**
     * 设置当前线程的用户上下文
     */
    public static void set(LoginUser user) {
        CONTEXT.set(user);
    }

    /**
     * 获取当前线程的用户上下文
     */
    public static LoginUser get() {
        return CONTEXT.get();
    }

    /**
     * 获取当前线程用户，若不存在则抛异常
     */
    public static LoginUser requireUser() {
        LoginUser user = CONTEXT.get();
        if (user == null) {
            throw new ClientException("未获取到当前登录用户");
        }
        return user;
    }

    /**
     * 获取当前用户 ID（未登录返回 null）
     */
    public static String getUserId() {
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getUserId();
    }

    /**
     * 获取当前用户名（未登录返回 null）
     */
    public static String getUsername() {
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getUsername();
    }

    /**
     * 获取当前角色（未登录返回 null）
     */
    public static String getRole() {
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getRole();
    }

    /**
     * 清理当前线程的用户上下文
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 判断是否已存在用户上下文
     */
    public static boolean hasUser() {
        return CONTEXT.get() != null;
    }
}

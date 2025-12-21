package com.nageoffer.ai.ragent.framework.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户的上下文快照
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginUser {

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 角色（如 admin/user）
     */
    private String role;
}
